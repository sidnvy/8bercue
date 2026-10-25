package dev.sid.fiveberinspector;

import android.os.Build;
import android.os.Process;
import android.os.RemoteException;
import org.json.JSONObject;
import android.util.Log;

/** Authenticated ADB Shell service. Typed reads and one-use, journaled operations only. */
public final class ProfileUserService extends IProfileService.Stub {
    private final String transport;
    public ProfileUserService() {this("ADB Shell");}
    ProfileUserService(String transport) {
        this.transport=transport;
        try {ServiceCaller.pinInstallation();} catch(Exception invalid){throw new SecurityException("Invalid service installation",invalid);}
    }
    private final String uiSession = OperationTokens.create();
    private CardChoicePlan pendingCardChoice;
    private RecoveryReviewPlan pendingRecoveryReview;
    private String displayedFingerprint;
    private String displayedCard;
    private String displayedLifecycleFingerprint;

    @Override
    public String getStatus() {
        try { ServiceCaller.requireApp(); } catch (Exception e) { return "Unauthorized service caller"; }
        Log.i("EightberShell", "getStatus called shellUid="+(Process.myUid()==2000));
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

    private void invalidatePreviews(){
        displayedFingerprint=null;displayedCard=null;displayedLifecycleFingerprint=null;
        pendingRecoveryReview=null;
        PortableProfileLifecycle.discardPreview();PortableProfileDownload.discardPreview();PhoneNotificationDelivery.discardCleanupPreview();
    }
    @Override public synchronized String prepareCardSelection(){
        try(ServiceCaller.ShellCall caller=ServiceCaller.asShell()){
            ReadOnlyRecovery.acquire();try{
                // Read-only selection also permits observing a card with an unresolved journal.
                // It never clears that journal or permits a mutation while recovery is required.
                pendingCardChoice=null;invalidatePreviews();
                pendingCardChoice=new CardChoicePlan(FiveberReaderSelector.discoverChoices(),android.os.SystemClock.elapsedRealtime());
                org.json.JSONArray choices=new org.json.JSONArray();
                for(CardChoicePlan.Choice choice:pendingCardChoice.choices)choices.put(new JSONObject()
                    .put("token",choice.token).put("reader",choice.target.reader)
                    .put("description",SystemHalAdapter.describeReader(choice.target.reader)).put("eidMasked",choice.target.eidMasked));
                return new JSONObject().put("ok",true).put("readOnly",true).put("validSeconds",90).put("choices",choices).toString();
            }finally{CardTransactionLock.release();}
        }catch(Throwable failure){pendingCardChoice=null;return safeOperationError("card-choices",failure);}
    }
    @Override public synchronized String selectCard(String opaqueChoice){
        try(ServiceCaller.ShellCall caller=ServiceCaller.asShell()){
            ReadOnlyRecovery.acquire();try{
                CardChoicePlan plan=pendingCardChoice;pendingCardChoice=null;invalidatePreviews();
                if(plan==null)throw new SecurityException("No pending card choice");
                FiveberReaderSelector.Target expected=plan.consume(opaqueChoice,android.os.SystemClock.elapsedRealtime());
                FiveberReaderSelector.Target selected=FiveberReaderSelector.selectExplicitly(expected);
                return new JSONObject().put("ok",true).put("readOnly",true).put("reader",selected.reader).put("eidMasked",selected.eidMasked).toString();
            }finally{CardTransactionLock.release();}
        }catch(Throwable failure){return safeOperationError("card-selection",failure);}
    }
    @Override
    public synchronized String listProfiles() throws RemoteException {
        Log.i("EightberShell", "listProfiles entered shellUid="+(Process.myUid()==2000));
        if (Process.myUid() != Process.SHELL_UID)
            return "{\"ok\":false,\"error\":\"WrongUid\"}";
        String step="discover";
        boolean acquired = false;
        try (ServiceCaller.ShellCall caller = ServiceCaller.asShell()) {
            step="caller"; ServiceCaller.requireApp();
            // Authenticate the real Binder caller FIRST. Only then run the
            // allowlisted read-only HAL work as our own Shell UID2000 process;
            // the app caller's Binder identity must not be inherited by HAL.
            // ShellCall retains the authenticated app UID for nested journal
            // checks while HAL calls execute with the service's Shell identity.
            step="lock"; ReadOnlyRecovery.acquire(); acquired = true;
            step="discover";FiveberReaderSelector.clearCompatibilityReport();
            // Discovery uses only standard GSMA ISD-R GetEID, and refuses any
            // malformed identity. No SIM1/SIM2 guessing or brand/version admission.
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
            boolean isolated=CardTransactionLock.persistentQuarantine();
            OperationJournal record=journal();
            boolean previousUnconfirmed=record.requiresReconciliation();
            boolean writeQualified=EnrolledWritePolicy.isAllowed(target.identityHash)&&!isolated&&!previousUnconfirmed;
            if(isolated){
                step="recovery-readback";
                Thread.sleep(300);
                java.util.List<CardProfileInventory.Entry> second=CardProfileInventory.list();
                if(!inventoryFingerprint(entries).equals(inventoryFingerprint(second)))throw new ReadOnlyRecovery.Failure("RECOVERY_INVENTORY_UNSTABLE");
            }
            java.util.List<ProfileLifecycleGuard.Row> policyRows=PortableProfileLifecycle.rows(entries);
            for (CardProfileInventory.Entry entry : entries) {
                String id = entry.iccid;
                profiles.put(new JSONObject().put("selectionId",ProfileUiIdentity.key(uiSession,target.identityHash,id)).put("provider", safeLabel(entry.provider))
                    .put("name", safeLabel(entry.name)).put("nickname", safeLabel(entry.nickname))
                    .put("state", entry.enabled ? "enabled" : "disabled")
                    .put("iccidMasked", id.substring(0,4) + "..." + id.substring(id.length()-4))
                    .put("canRename",writeQualified&&ReaderDescriptor.lifecycleAllowed(target.reader,ProfileLifecycleGuard.Operation.RENAME,entry.enabled)&&allowed(target.identityHash,policyRows,entry,ProfileLifecycleGuard.Operation.RENAME))
                    .put("canEnable",writeQualified&&ReaderDescriptor.lifecycleAllowed(target.reader,ProfileLifecycleGuard.Operation.ENABLE,entry.enabled)&&allowed(target.identityHash,policyRows,entry,ProfileLifecycleGuard.Operation.ENABLE))
                    .put("canDisable",writeQualified&&ReaderDescriptor.lifecycleAllowed(target.reader,ProfileLifecycleGuard.Operation.DISABLE,entry.enabled)&&allowed(target.identityHash,policyRows,entry,ProfileLifecycleGuard.Operation.DISABLE))
                    .put("canDelete",writeQualified&&ReaderDescriptor.lifecycleAllowed(target.reader,ProfileLifecycleGuard.Operation.DELETE,entry.enabled)&&allowed(target.identityHash,policyRows,entry,ProfileLifecycleGuard.Operation.DELETE)));
            }
            FiveberReaderSelector.Target finalCard=FiveberReaderSelector.discover();
            if(!target.identityHash.equals(finalCard.identityHash)||!target.reader.equals(finalCard.reader))throw new SecurityException("Card changed during inventory");
            java.util.Properties previous=record.read();
            String recoveryScope=isolated?ReadOnlyRecovery.observed(target.identityHash,inventoryFingerprint(entries))
                :previousUnconfirmed?ReadOnlyRecovery.scope(previous,target.identityHash):"none";
            displayedFingerprint = inventoryFingerprint(entries);
            displayedCard = target.identityHash;
            displayedLifecycleFingerprint = null;
            try { displayedLifecycleFingerprint = ProfileLifecycleGuard.selectionFingerprint(PortableProfileLifecycle.rows(entries)); }
            catch (SecurityException unsupportedMutationState) { /* Still display an empty/inactive card for recovery. */ }
            reply.put("profiles", profiles).put("count", profiles.length()).put("writeQualified",writeQualified)
                .put("reader",target.reader).put("eidMasked",target.eidMasked).put("description",SystemHalAdapter.describeReader(target.reader));
            reply.put("connectionMethod","adb_shell");
            reply.put("transport",transport+" UID2000 / "+target.reader);
            CompatibilityReport compatibility=FiveberReaderSelector.compatibilityReport();
            reply.put("compatibility",compatibilityJson(compatibility,true));
            reply.put("connectionNotice","Card connection: "+compatibility.route()+". "+
                (target.reader.startsWith(ReaderDescriptor.TELEPHONY_PREFIX)?"Experimental system-policy workaround; another ROM may block it.":target.reader.startsWith(ReaderDescriptor.HIDL_PREFIX)?"Card reads verified; management writes are not implemented for HIDL.":"Card reads verified; changes still require confirmation."));
            reply.put("readOnly",true);
            if(isolated||previousUnconfirmed)reply.put("recoveryState","same_card".equals(recoveryScope)?"READ_ONLY_RECOVERED":"READ_ONLY_CURRENT_CARD")
                .put("recoveryScope",recoveryScope).put("changesBlocked",true)
                .put("previousOperation",previous.getProperty("operation", "")).put("previousOutcome",previous.getProperty("status","unknown"))
                .put("recoveryReviewEligible","same_card".equals(recoveryScope)&&displayedLifecycleFingerprint!=null&&EnrolledWritePolicy.isAllowed(target.identityHash)
                    &&previous.getProperty("operation","").matches("enable|disable|rename|switch")
                    &&previous.getProperty("status","").matches("running|unknown|observed_unknown"));
            Log.i("EightberShell", "listProfiles success reader="+target.reader+" count="+reply.optInt("count",-1));
            return reply.toString();
        } catch (Throwable ex) {
            // Avoid logging APDU payloads, ICCIDs, EIDs or server credentials.
            Throwable cause = ex;
            while (cause instanceof java.lang.reflect.InvocationTargetException &&
                ((java.lang.reflect.InvocationTargetException)cause).getTargetException()!=null)
                cause=((java.lang.reflect.InvocationTargetException)cause).getTargetException();
            Log.w("EightberShell", "listProfiles failed stage="+step+
                " cause="+cause.getClass().getSimpleName());
            // Return a structured, sensitive-data-free error envelope over Binder.
            displayedFingerprint=null;displayedCard=null;displayedLifecycleFingerprint=null;
            try {
                JSONObject diagnostic=new JSONObject().put("ok",false).put("stage",step)
                    .put("error",cause.getClass().getSimpleName());
                SecureElementFailure access=SecureElementFailure.find(ex);
                if(access!=null)diagnostic.put("capabilityError",access.reason.name());
                String recovery=ReadOnlyRecovery.code(ex);
                if(!recovery.isEmpty())diagnostic.put("recoveryError",recovery).put("reviewRequired",true);
                if(!step.equals("caller")&&!step.equals("lock"))diagnostic.put("compatibility",compatibilityJson(FiveberReaderSelector.compatibilityReport(),false));
                if(cause instanceof PhoneNativeLpac.StageException)
                    diagnostic.put("phase",((PhoneNativeLpac.StageException)cause).phase);
                return diagnostic.toString();
            }catch(Exception jsonError){
                return "{\"ok\":false,\"error\":\"EncodingFailure\"}";
            }
        } finally { if (acquired) CardTransactionLock.release(); }
    }

    private static JSONObject compatibilityJson(CompatibilityReport report,boolean profilesVerified)throws Exception {
        if(report==null)return new JSONObject();
        org.json.JSONArray attempts=new org.json.JSONArray();
        for(CompatibilityReport.Attempt attempt:report.attempts())attempts.put(new JSONObject()
            .put("backend",attempt.backend.name()).put("reader",attempt.reader).put("stage",attempt.stage.name())
            .put("condition",attempt.reason==null?"VERIFIED":attempt.reason.name()));
        return new JSONObject().put("schema",1).put("readOnlyProbe",true).put("selectedBackend",report.selectedBackend())
            .put("selectedReader",report.selectedReader()).put("route",report.route()).put("details",report.summary())
            .put("profileReadVerified",profilesVerified).put("writesTested",false).put("attempts",attempts);
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
        try {ProfileLifecycleGuard.validate(operation,before,entry.iccid,entry.nickname);LabSupport.validateMutation(card,operation,before,entry.iccid);ShellOperationScope.validateMutation(card,operation,before,entry.iccid);return true;}
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
    private static final class RecoveryReviewPlan {
        final String token,tx,card,reader,snapshot,recordDigest,operation;
        final boolean alreadyReviewed;
        final long created;
        RecoveryReviewPlan(String tx,String card,String reader,String snapshot,String recordDigest,String operation,boolean alreadyReviewed) {
            this.tx=tx;this.card=card;this.reader=reader;this.snapshot=snapshot;
            this.recordDigest=recordDigest;this.operation=operation;this.alreadyReviewed=alreadyReviewed;
            this.token=OperationTokens.create();
            this.created=android.os.SystemClock.elapsedRealtime();
        }
        void requireFresh(String candidate) {
            if(candidate==null||!token.equals(candidate)||
                android.os.SystemClock.elapsedRealtime()-created>90000)
                throw new SecurityException("Recovery confirmation expired");
        }
    }

    /** Caller already holds ReadOnlyRecovery's original cross-process lock. */
    private RecoveryReviewPlan observeLifecycleForReview(OperationJournal journal) throws Exception {
        java.util.Properties record=journal.read();
        boolean alreadyReviewed="reviewed_observed".equals(journal.statusAfterReadOnlyReconciliation());
        if(!"2".equals(record.getProperty("schema"))||
            !record.getProperty("operation","").matches("enable|disable|rename|switch")||
            !record.getProperty("status","").matches("running|unknown|observed_unknown")||
            !(journal.requiresReconciliation()||alreadyReviewed)||
            (alreadyReviewed&&!CardTransactionLock.persistentQuarantine()))
            throw new SecurityException("No eligible unresolved lifecycle operation");
        FiveberReaderSelector.Target card=FiveberReaderSelector.discover();
        if(!card.identityHash.equals(record.getProperty("card"))||
            !EnrolledWritePolicy.isAllowed(card.identityHash))
            throw new SecurityException("Recovery must use the original permitted card");
        NativeLpacBundle.ensureInstalled();
        PhoneNativeLpac.listProfiles();
        String initial=ProfileLifecycleGuard.fingerprint(
            PortableProfileLifecycle.rows(CardProfileInventory.list()));
        Thread.sleep(300);
        String confirmed=ProfileLifecycleGuard.fingerprint(
            PortableProfileLifecycle.rows(CardProfileInventory.list()));
        FiveberReaderSelector.Target same=FiveberReaderSelector.discover();
        if(!initial.equals(confirmed)||!same.identityHash.equals(card.identityHash)||
            !same.reader.equals(card.reader))
            throw new ReadOnlyRecovery.Failure("RECOVERY_INVENTORY_UNSTABLE");
        if(alreadyReviewed&&!confirmed.equals(journal.reviewedLifecycleSnapshot()))
            throw new SecurityException("Card changed after durable review; manual investigation required");
        return new RecoveryReviewPlan(record.getProperty("txId"),card.identityHash,
            card.reader,confirmed,OperationJournal.recordDigest(record),record.getProperty("operation"),alreadyReviewed);
    }

    /**
     * Prepare ONLY a read-only, single-use review proposal. A readable card cannot by itself
     * authorize future writes, and the previous mutation outcome is never declared successful.
     */
    @Override public synchronized String prepareRecoveryReview() {
        pendingRecoveryReview=null;
        try(ServiceCaller.ShellCall caller=ServiceCaller.asShell()) {
            ServiceCaller.requireApp();
            ReadOnlyRecovery.acquire();
            try {
                RecoveryReviewPlan proposal=observeLifecycleForReview(journal());
                pendingRecoveryReview=proposal;
                return new JSONObject().put("ok",true).put("token",proposal.token)
                    .put("finishingPriorReview",proposal.alreadyReviewed)
                    .put("operation",proposal.operation).put("inventoryChanged",
                        !proposal.snapshot.equals(journal().read().getProperty("before","")))
                    .put("validSeconds",90).put("previousOutcome","unknown")
                    .put("requiresExplicitConsent",true).toString();
            }finally{CardTransactionLock.release();}
        }catch(Throwable e){pendingRecoveryReview=null;return safeOperationError("recovery-review-preview",e);}
    }

    /**
     * Consume the proposal, reread the entire same card twice under the original exclusion,
     * verify quiescence, then archive any worker quarantine and persist an immutable review
     * disposition. Never retries, enables, disables, renames or deletes a profile.
     */
    @Override public synchronized String confirmRecoveryReview(String opaqueToken) {
        RecoveryReviewPlan expected=pendingRecoveryReview;
        pendingRecoveryReview=null; // consume even when verification or persistence fails
        try(ServiceCaller.ShellCall caller=ServiceCaller.asShell()) {
            ServiceCaller.requireApp();
            if(expected==null)throw new SecurityException("No recovery proposal");
            expected.requireFresh(opaqueToken);
            ReadOnlyRecovery.acquire();
            try {
                OperationJournal record=journal();
                RecoveryReviewPlan actual=observeLifecycleForReview(record);
                if(!actual.tx.equals(expected.tx)||!actual.card.equals(expected.card)||
                    !actual.reader.equals(expected.reader)||
                    !actual.snapshot.equals(expected.snapshot)||
                    !actual.recordDigest.equals(expected.recordDigest)||
                    !actual.operation.equals(expected.operation)||
                    actual.alreadyReviewed!=expected.alreadyReviewed)
                    throw new SecurityException("Recovery evidence changed after confirmation");
                // Worker quiescence MUST be proven before any durable review disposition,
                // including the important no-quarantine-marker case (issue #14).
                ReadOnlyRecovery.requireReviewQuiescent();
                String authorization=IntentDigests.of(expected.token);
                // Commit the review FIRST. With a persistent quarantine, the marker still
                // blocks writes until separately archived. If interrupted, a fresh explicit
                // consent and identical inventory can resume archival without altering history.
                if(!expected.alreadyReviewed)
                    record.reviewObservedLifecycle(expected.tx,expected.card,expected.snapshot,
                        expected.recordDigest,authorization);
                if(CardTransactionLock.persistentQuarantine())
                    ReadOnlyRecovery.archiveAfterReview(expected.card,expected.recordDigest,
                        expected.snapshot,authorization);
                invalidatePreviews();
                return new JSONObject().put("ok",true).put("status","reviewed_observed")
                    .put("originalOutcome","unknown").put("previousOperation",expected.operation)
                    .put("priorOperationRetried",false).put("safetyRecordsPreserved",true).toString();
            }finally{CardTransactionLock.release();}
        }catch(Throwable e){return safeOperationError("recovery-review-confirm",e);}
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
            if(CardTransactionLock.persistentQuarantine())return result.put("reviewRequired",true)
                .put("requiresReconciliation",true).put("recoveryState","SAFETY_PAUSED")
                .put("changesBlocked",true).put("notificationStatus","unavailable").toString();
            for(String key:p.stringPropertyNames())if(key.startsWith("download"))result.put(key,p.getProperty(key));
            JSONObject notification = PhoneNotificationDelivery.status();
            for (String key : new String[]{"notificationStatus", "notificationReviewRequired", "notificationWorkerStopped", "notificationCleanupAvailable", "notificationPendingCount"})
                if (notification.has(key)) result.put(key, notification.get(key));
            return result.toString();
        } catch (Throwable e) { return safeOperationError("recovery", e); }
    }
    @Override public synchronized String reconcileOperation() {
        // listProfiles owns its authenticated Shell scope. Keep this caller's
        // Binder identity intact so it can enter that scope exactly once.
        try {
            ServiceCaller.requireApp();
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
            ReadOnlyRecovery.acquire(); acquired = true;
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
            JSONObject reply=new JSONObject().put("ok",false).put("phase",phase)
                .put("status", "execute".equals(phase) ? "unknown" : "not_started")
                .put("reviewRequired", "execute".equals(phase))
                .put("error",cause.getClass().getSimpleName());
            SecureElementFailure access=SecureElementFailure.find(cause);
            if(access!=null)reply.put("capabilityError",access.reason.name());
            String recovery=ReadOnlyRecovery.code(cause);
            if(!recovery.isEmpty())reply.put("recoveryError",recovery).put("reviewRequired",true);
            return reply.toString();
        }catch(Exception e){return "{}";}
    }

    @Override
    public void destroy() {
        try { ServiceCaller.requireApp(); } catch (Exception denied) { throw new SecurityException("Unauthorized shutdown"); }
        if (CardTransactionLock.isActive()) throw new SecurityException("Transaction active");
        System.exit(0);
    }
}
