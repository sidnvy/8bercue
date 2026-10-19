package dev.sid.fiveberinspector;

import android.os.Build;
import android.os.Process;
import android.os.RemoteException;
import org.json.JSONObject;
import android.util.Log;

/**
 * Shizuku UserService: the preferred cross-device transport.
 *
 * Shizuku starts this process as ADB Shell UID2000 and transports requests
 * over Binder. Unlike the old Shell Companion's Unix domain socket, this
 * survives Motorola's SELinux untrusted_app -> shell connectto restriction.
 *
 * Binder exposes reads and two-phase, opaque-token operations for the discovered card.
 * No arbitrary APDU, caller-supplied ICCID or generic shell command.
 * Full card identity and active-profile protections apply to each write.
 */
public final class ProfileUserService extends IProfileService.Stub {
    private final String transport;
    public ProfileUserService() {this("Shizuku");}
    ProfileUserService(String transport) {
        this.transport=transport;
        try {ServiceCaller.pinInstallation();} catch(Exception invalid){throw new SecurityException("Invalid service installation",invalid);}
    }
    private String displayedFingerprint;
    private String displayedCard;
    private String displayedLifecycleFingerprint;

    @Override
    public String getStatus() {
        try { ServiceCaller.requireApp(); } catch (Exception e) { return "Unauthorized service caller"; }
        Log.i("FiveberShizuku", "getStatus called shellUid="+(Process.myUid()==2000));
        boolean shell = Process.myUid() == Process.SHELL_UID;
        String classpath = System.getenv("CLASSPATH");
        boolean apkPath = classpath != null &&
            classpath.contains("/dev.sid.fiveberinspector-") &&
            classpath.contains("/base.apk");
        return transport+" UserService / UID "+Process.myUid()+
            " / Android "+Build.VERSION.RELEASE+
            " / "+Build.MODEL+
            " / apkClasspath="+apkPath+" / read + scoped switch preview";
    }

    @Override
    public synchronized String listProfiles() throws RemoteException {
        Log.i("FiveberShizuku", "listProfiles entered shellUid="+(Process.myUid()==2000));
        if (Process.myUid() != Process.SHELL_UID)
            return "{\"ok\":false,\"error\":\"WrongUid\"}";
        String step="discover";
        boolean acquired = false;
        boolean cleanIdentity = false;
        long originalBinderIdentity = 0L;
        try {
            step="caller"; ServiceCaller.requireApp();
            // Authenticate the real Binder caller FIRST. Only then run the
            // allowlisted read-only HAL work as our own Shell UID2000 process;
            // the app caller's Binder identity must not be inherited by HAL.
            originalBinderIdentity = android.os.Binder.clearCallingIdentity();
            cleanIdentity = true;
            step="lock"; CardTransactionLock.acquire(); acquired = true;
            step="discover";
            // Discovery uses only standard GSMA ISD-R GetEID, and refuses any
            // un-enrolled card. No SIM1/SIM2 guessing or isRemovable logic.
            FiveberReaderSelector.Target target = FiveberReaderSelector.discover();
            step="bundle";

            // All six binary components are bundled in the signed APK and
            // SHA-256 verified before use. No host-side lpac is required.
            NativeLpacBundle.ensureInstalled();
            step="lpac";

            // The native libeuicc engine independently checks the EID again
            // and only invokes GetEID/GetProfilesInfo via the HAL.
            JSONObject reply = PhoneNativeLpac.listProfiles();
            // Return the same authoritative order used by preview selection.
            java.util.List<CardProfileInventory.Entry> entries = CardProfileInventory.list();
            org.json.JSONArray profiles = new org.json.JSONArray();
            boolean writeQualified=EnrolledWritePolicy.isAllowed(target.identityHash);
            java.util.List<ProfileLifecycleGuard.Row> policyRows=PortableProfileLifecycle.rows(entries);
            for (CardProfileInventory.Entry entry : entries) {
                String id = entry.iccid;
                profiles.put(new JSONObject().put("provider", safeLabel(entry.provider))
                    .put("name", safeLabel(entry.name)).put("nickname", safeLabel(entry.nickname))
                    .put("state", entry.enabled ? "enabled" : "disabled")
                    .put("iccidMasked", id.substring(0,4) + "..." + id.substring(id.length()-4))
                    .put("canRename",writeQualified&&allowed(target.identityHash,policyRows,entry,ProfileLifecycleGuard.Operation.RENAME))
                    .put("canEnable",writeQualified&&allowed(target.identityHash,policyRows,entry,ProfileLifecycleGuard.Operation.ENABLE))
                    .put("canDisable",writeQualified&&allowed(target.identityHash,policyRows,entry,ProfileLifecycleGuard.Operation.DISABLE))
                    .put("canDelete",writeQualified&&allowed(target.identityHash,policyRows,entry,ProfileLifecycleGuard.Operation.DELETE)));
            }
            FiveberReaderSelector.Target finalCard=FiveberReaderSelector.discover();
            if(!target.identityHash.equals(finalCard.identityHash)||!target.reader.equals(finalCard.reader))throw new SecurityException("Card changed during inventory");
            displayedFingerprint = inventoryFingerprint(entries);
            displayedCard = target.identityHash;
            displayedLifecycleFingerprint = null;
            try { displayedLifecycleFingerprint = ProfileLifecycleGuard.selectionFingerprint(PortableProfileLifecycle.rows(entries)); }
            catch (SecurityException unsupportedMutationState) { /* Still display an empty/inactive card for recovery. */ }
            reply.put("profiles", profiles).put("count", profiles.length()).put("writeQualified",writeQualified);
            reply.put("shizuku","Shizuku".equals(transport));
            reply.put("transport",transport+" UID2000 / HAL "+target.reader);
            Log.i("FiveberShizuku", "listProfiles success reader="+target.reader+" count="+reply.optInt("count",-1));
            reply.put("readOnly",true);
            return reply.toString();
        } catch (Throwable ex) {
            // Avoid logging APDU payloads, ICCIDs, EIDs or server credentials.
            Throwable cause = ex;
            while (cause instanceof java.lang.reflect.InvocationTargetException &&
                ((java.lang.reflect.InvocationTargetException)cause).getTargetException()!=null)
                cause=((java.lang.reflect.InvocationTargetException)cause).getTargetException();
            Log.w("FiveberShizuku", "listProfiles failed stage="+step+
                " cause="+cause.getClass().getSimpleName());
            // On some Shizuku versions RemoteException from the UserService
            // is swallowed by Binder and becomes a null String. Return a
            // structured, sensitive-data-free error envelope instead.
            try {
                JSONObject diagnostic=new JSONObject().put("ok",false).put("stage",step)
                    .put("error",cause.getClass().getSimpleName());
                if(cause instanceof PhoneNativeLpac.StageException)
                    diagnostic.put("phase",((PhoneNativeLpac.StageException)cause).phase);
                return diagnostic.toString();
            }catch(Exception jsonError){
                return "{\"ok\":false,\"error\":\"EncodingFailure\"}";
            }
        } finally {
            try { if (acquired) CardTransactionLock.release(); }
            finally {
                if (cleanIdentity) android.os.Binder.restoreCallingIdentity(originalBinderIdentity);
            }
        }
    }

    @Override
    public synchronized String prepareSwitch(int profileIndex) {
        try (ServiceCaller.ShellCall caller = ServiceCaller.asShell()) {
            requireSelectionCurrent();
            return PortableProfileLifecycle.prepare("enable", profileIndex, "", displayedLifecycleFingerprint, displayedCard).toString();
        } catch (Throwable e) {
            return safeOperationError("prepare",e);
        }
    }

    @Override
    public synchronized String executeSwitch(String opaqueToken) {
        try (ServiceCaller.ShellCall caller = ServiceCaller.asShell()) {
            return executeJournaled(() -> PortableProfileLifecycle.journalBinding(opaqueToken), () -> PortableProfileSwitch.execute(opaqueToken));
        } catch (Throwable e) {
            return safeOperationError("execute",e);
        }
    }

    private OperationJournal journal() throws Exception {
        ServiceCaller.requireApp();
        if (Process.myUid() != Process.SHELL_UID) throw new SecurityException("WrongUid");
        java.io.File dir = new java.io.File("/data/local/tmp/fiveber-rescue-state");
        if (!dir.exists() && !dir.mkdir()) throw new java.io.IOException("Journal unavailable");
        android.system.Os.chmod(dir.getAbsolutePath(), 0700);
        return new OperationJournal(dir);
    }
    private interface Mutation { JSONObject run() throws Exception; }
    private interface Intent { OperationJournal.Binding get() throws Exception; }
    private String executeJournaled(Intent intent, Mutation mutation) throws Exception {
        ServiceCaller.requireApp();
        CardTransactionLock.beginCoordinated();
        try {
            OperationJournal record = journal();
            OperationJournal.Binding operation = intent.get();
            record.begin(operation); // Fsync BEFORE entering any mutating backend.
            try {
                JSONObject result = mutation.run();
                boolean verified = result.optBoolean("verified", false);
                boolean uncertain = result.optBoolean("reviewRequired", false);
                java.util.Properties diagnostics=new java.util.Properties();
                JSONObject detail=result.optJSONObject("downloadDiagnostics");
                if(detail!=null)for(java.util.Iterator<String> keys=detail.keys();keys.hasNext();) {String key=keys.next();diagnostics.setProperty(key,detail.getString(key));}
                record.finish(verified && !uncertain ? "verified" : "unknown",diagnostics);
                return result.toString();
            } catch (Throwable e) {
                try { record.finish("unknown"); } catch (Exception persistenceFailure) { CardTransactionLock.quarantine(); }
                return new JSONObject().put("ok", false).put("status", "unknown")
                    .put("reviewRequired", true).put("error", e.getClass().getSimpleName()).toString();
            }
        } finally { CardTransactionLock.endCoordinated(); }
    }
    private static String inventoryFingerprint(java.util.List<CardProfileInventory.Entry> entries) throws Exception {
        java.util.List<DownloadGuard.Row> rows = new java.util.ArrayList<>();
        for (CardProfileInventory.Entry p : entries) rows.add(new DownloadGuard.Row(p.iccid,p.enabled,
            p.provider.length()+":"+p.provider+p.name.length()+":"+p.name+p.nickname.length()+":"+p.nickname));
        return DownloadGuard.fingerprint(rows);
    }
    private static boolean allowed(String card,java.util.List<ProfileLifecycleGuard.Row> before,CardProfileInventory.Entry entry,ProfileLifecycleGuard.Operation operation){
        try {ProfileLifecycleGuard.validate(operation,before,entry.iccid,entry.nickname);LabSupport.validateMutation(card,operation,before,entry.iccid);return true;}
        catch(Exception denied){return false;}
    }
    private static String safeLabel(String value) {
        String safe = value.replaceAll("[\\p{Cntrl}]", "").replaceAll("[0-9]{8,}", "[hidden]");
        return safe.substring(0, Math.min(96, safe.length()));
    }
    private void requireSelectionCurrent() throws Exception {
        requireReconciled();
        CardTransactionLock.acquire();
        try {
        FiveberReaderSelector.Target card = FiveberReaderSelector.discover();
        String now = inventoryFingerprint(CardProfileInventory.list());
        if (displayedLifecycleFingerprint == null || displayedFingerprint == null || !now.equals(displayedFingerprint) || !card.identityHash.equals(displayedCard))
            throw new SecurityException("RefreshRequired");
        } finally { CardTransactionLock.release(); }
    }
    private void requireReconciled() throws Exception {
        if (journal().requiresReconciliation()) throw new SecurityException("ReconciliationRequired");
    }
    @Override public synchronized String prepareRename(int profileIndex, String nickname) {
        try (ServiceCaller.ShellCall caller = ServiceCaller.asShell()) { requireSelectionCurrent(); return PortableProfileLifecycle.prepare("rename", profileIndex, nickname, displayedLifecycleFingerprint, displayedCard).toString(); }
        catch (Throwable e) { return safeOperationError("prepare", e); }
    }
    @Override public synchronized String prepareDelete(int profileIndex) {
        try (ServiceCaller.ShellCall caller = ServiceCaller.asShell()) { requireSelectionCurrent(); return PortableProfileLifecycle.prepare("delete", profileIndex, "", displayedLifecycleFingerprint, displayedCard).toString(); }
        catch (Throwable e) { return safeOperationError("prepare", e); }
    }
    @Override public synchronized String prepareDisable(int profileIndex) {
        try (ServiceCaller.ShellCall caller = ServiceCaller.asShell()) { requireSelectionCurrent(); return PortableProfileLifecycle.prepare("disable", profileIndex, "", displayedLifecycleFingerprint, displayedCard).toString(); }
        catch (Throwable e) { return safeOperationError("prepare", e); }
    }
    @Override public synchronized String executeOperation(String opaqueToken) {
        try (ServiceCaller.ShellCall caller = ServiceCaller.asShell()) { return executeJournaled(() -> PortableProfileLifecycle.journalBinding(opaqueToken), () -> PortableProfileLifecycle.execute(opaqueToken)); }
        catch (Throwable e) { return safeOperationError("execute", e); }
    }
    @Override public synchronized String prepareDownload(String activationCode, String confirmationCode) {
        try (ServiceCaller.ShellCall caller = ServiceCaller.asShell()) { requireReconciled(); return PortableProfileDownload.prepare(activationCode, confirmationCode).toString(); }
        catch (Throwable e) { return safeOperationError("prepare", e); }
    }
    @Override public synchronized String executeDownload(String opaqueToken) {
        try (ServiceCaller.ShellCall caller = ServiceCaller.asShell()) { return executeJournaled(() -> PortableProfileDownload.journalBinding(opaqueToken), () -> PortableProfileDownload.execute(opaqueToken)); }
        catch (Throwable e) { return safeOperationError("execute", e); }
    }
    @Override public synchronized String prepareNotificationCleanup() {
        try (ServiceCaller.ShellCall caller = ServiceCaller.asShell()) { requireReconciled(); return PhoneNotificationDelivery.prepareCleanup().toString(); }
        catch (Throwable e) { return safeOperationError("notification-cleanup-preview", e); }
    }
    @Override public synchronized String executeNotificationCleanup(String opaqueToken) {
        try (ServiceCaller.ShellCall caller = ServiceCaller.asShell()) {
            ServiceCaller.requireApp(); CardTransactionLock.beginCoordinated();
            try { requireReconciled(); return PhoneNotificationDelivery.executeCleanup(opaqueToken).toString(); }
            finally { CardTransactionLock.endCoordinated(); }
        }
        catch (Throwable e) { return safeOperationError("notification-cleanup", e); }
    }
    @Override public synchronized String getOperationStatus() {
        try (ServiceCaller.ShellCall caller = ServiceCaller.asShell()) {
            java.util.Properties p = journal().read();
            String status = journal().statusAfterReadOnlyReconciliation();
            boolean review = journal().requiresReconciliation() || CardTransactionLock.isQuarantined();
            JSONObject result = new JSONObject().put("ok", true).put("status", status)
                .put("operation", p.getProperty("operation", ""))
                .put("originalOutcome", p.getProperty("status", "unknown"))
                .put("reviewRequired", review)
                .put("requiresReconciliation", review);
            for(String key:p.stringPropertyNames())if(key.startsWith("download"))result.put(key,p.getProperty(key));
            JSONObject notification = PhoneNotificationDelivery.status();
            for (String key : new String[]{"notificationStatus", "notificationReviewRequired", "notificationWorkerStopped", "notificationCleanupAvailable", "notificationPendingCount"})
                if (notification.has(key)) result.put(key, notification.get(key));
            return result.toString();
        } catch (Throwable e) { return safeOperationError("recovery", e); }
    }
    @Override public synchronized String reconcileOperation() {
        try (ServiceCaller.ShellCall caller = ServiceCaller.asShell()) {
            JSONObject inventory = new JSONObject(listProfiles());
            if (inventory.has("error") || !inventory.has("profiles")) return inventory.toString();
            // An inventory alone cannot prove the intent of a lost transaction.
            // Never clear the execution block just because a fresh card is readable;
            // retain an explicit unknown outcome, never retry the previous action.
            OperationJournal record = journal();
            String status = record.statusAfterReadOnlyReconciliation();
            boolean review = record.requiresReconciliation();
            return new JSONObject().put("ok", true).put("status", status)
                .put("readOnly", true).put("reviewRequired", review).put("inventory", inventory).toString();
        } catch (Throwable e) { return safeOperationError("reconcile", e); }
    }
    @Override public synchronized String getCardInfo() {
        boolean acquired = false;
        try (ServiceCaller.ShellCall caller = ServiceCaller.asShell()) {
            ServiceCaller.requireApp();
            CardTransactionLock.acquire(); acquired = true;
            FiveberReaderSelector.Target target = FiveberReaderSelector.discover();
            // Optional GetEuiccInfo2 remains read-only; unsupported fields are omitted.
            // EID is read and checked against the selected full identity before exporting only its mask.
            JSONObject details = CardCapabilities.read();
            // Independent Java ES10c Profile inventory is compared against
            // the two historical LPA/HAL fingerprint pins in memory only.
            // No ICCIDs or their digests leave this privileged Shell process.
            java.util.List<CardProfileInventory.Entry> live = CardProfileInventory.list();
            boolean profileFingerprintMatch =
                LabSupport.baseline(live);
            return details.put("ok", true).put("cardVerified", true)
                .put("reader", target.reader).put("engine", "lpac/libeuicc 2.3.0")
                .put("profileHashesMatchHistoricalBaseline", profileFingerprintMatch)
                .put("storageSupported", details.has("freeNonvolatileMemoryBytes"))
                .put("installedProfileCount", live.size()).toString();
        } catch (Throwable e) { return safeOperationError("card-info", e); }
        finally { if (acquired) CardTransactionLock.release(); }
    }

    private static String safeOperationError(String phase,Throwable cause) {
        // Never return exception messages: some include APDUs/card data.
        try {
            return new JSONObject().put("ok",false).put("phase",phase)
                .put("status", "execute".equals(phase) ? "unknown" : "not_started")
                .put("reviewRequired", "execute".equals(phase))
                .put("error",cause.getClass().getSimpleName()).toString();
        }catch(Exception e){return "{}";}
    }

    @Override
    public void destroy() {
        try { ServiceCaller.requireApp(); } catch (Exception denied) { throw new SecurityException("Unauthorized shutdown"); }
        if (CardTransactionLock.isActive()) throw new SecurityException("Transaction active");
        System.exit(0);
    }
}
