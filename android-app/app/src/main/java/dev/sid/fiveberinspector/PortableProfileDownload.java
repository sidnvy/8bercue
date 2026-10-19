package dev.sid.fiveberinspector;

import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import org.json.JSONObject;
import java.security.SecureRandom;
import java.util.*;

/** Two-phase installation. Never enables, retries, deletes or restores a profile. */
public final class PortableProfileDownload {
    private static final long TTL=90_000L;
    private static Plan pending;
    private static final class Plan {
        final String token,reader,hash,identityHash,fingerprint,confirmation;
        final ActivationCode activation;
        final long created;
        Plan(String token,FiveberReaderSelector.Target card,String fingerprint,
                ActivationCode activation,String confirmation) {
            this.token=token;reader=card.reader;hash=card.identityHash;identityHash=card.identityHash;this.fingerprint=fingerprint;
            this.activation=activation;this.confirmation=confirmation;created=SystemClock.elapsedRealtime();
        }
    }
    private static void requireShell() {
        if(Process.myUid()!=Process.SHELL_UID)throw new SecurityException("Shizuku Shell UID required");
    }
    private static boolean qualified(String hash) {
        return EnrolledWritePolicy.isAllowed(hash);
    }
    private static List<DownloadGuard.Row> rows(List<CardProfileInventory.Entry> profiles) {
        List<DownloadGuard.Row> result=new ArrayList<>();
        for(CardProfileInventory.Entry p:profiles)result.add(new DownloadGuard.Row(p.iccid,p.enabled,
            p.provider.length()+":"+p.provider+p.name.length()+":"+p.name+p.nickname.length()+":"+p.nickname));
        return result;
    }
    public static synchronized JSONObject prepare(String raw,String confirmation) throws Exception {
        requireShell();
        CardTransactionLock.acquire();
        try {
            pending=null;
            ActivationCode activation=ActivationCode.parse(raw);
            String secret=activation.confirmation(confirmation);
            FiveberReaderSelector.Target card=FiveberReaderSelector.discover();LabSupport.validateDownload(card.identityHash,PortableProfileLifecycle.rows(CardProfileInventory.list()));
            PhoneNativeLpac.listProfiles();
            List<DownloadGuard.Row> before=rows(CardProfileInventory.list());
            String fingerprint=DownloadGuard.fingerprint(before);
            if(before.size()>=16)throw new SecurityException("Profile inventory at this build's supported limit");
            byte[] bytes=new byte[24];new SecureRandom().nextBytes(bytes);
            StringBuilder token=new StringBuilder();
            for(byte b:bytes)token.append(String.format(Locale.ROOT,"%02x",b&255));
            pending=new Plan(token.toString(),card,fingerprint,activation,secret);
            return new JSONObject().put("token",pending.token).put("server",activation.host)
                .put("confirmationProvided",!secret.isEmpty()).put("validSeconds",90)
                .put("writeQualified",qualified(card.identityHash)).put("readOnlyPreview",!qualified(card.identityHash))
                .put("willEnable",false).put("beforeCount",before.size());
        } finally {CardTransactionLock.release();}
    }
    public static synchronized OperationJournal.Binding journalBinding(String token)throws Exception {
        if(pending==null||token==null||!pending.token.equals(token)||SystemClock.elapsedRealtime()-pending.created>TTL)
            throw new SecurityException("Download preview expired or invalid");
        return new OperationJournal.Binding("download",pending.identityHash,IntentDigests.of(pending.token),
            pending.fingerprint,IntentDigests.of(pending.activation.host));
    }
    public static JSONObject execute(String token) throws Exception {
        requireShell();
        Plan plan;
        synchronized(PortableProfileDownload.class) {
            if(pending==null || token==null || !pending.token.equals(token)
                    || SystemClock.elapsedRealtime()-pending.created>TTL) {
                pending=null;throw new SecurityException("Download preview expired or invalid");
            }
            CardTransactionLock.acquire();
            plan=pending;pending=null;
        }
        boolean safeToUnlock=true;
        try {
            if(!qualified(plan.hash))throw new SecurityException("Invalid card identity or local test scope does not permit this card");
            FiveberReaderSelector.Target card=FiveberReaderSelector.discover();LabSupport.validateDownload(card.identityHash,PortableProfileLifecycle.rows(CardProfileInventory.list()));
            if(!plan.reader.equals(card.reader) || !plan.hash.equals(card.identityHash) || !plan.identityHash.equals(card.identityHash))
                throw new SecurityException("Card identity changed since preview");
            PhoneNativeLpac.listProfiles();
            List<DownloadGuard.Row> before=rows(CardProfileInventory.list());
            if(!plan.fingerprint.equals(DownloadGuard.fingerprint(before)))
                throw new SecurityException("Profiles changed since preview; review again");
            PhoneProfileDownload.Outcome attempt=PhoneProfileDownload.run(plan.activation,plan.confirmation,plan.reader,plan.hash);
            safeToUnlock=attempt.workerStopped;
            if(!safeToUnlock)return new JSONObject().put("ok",false).put("status","unknown")
                .put("reviewRequired",true).put("cardReadRequired",true)
                .put("workerStopped",false).put("retryAllowed",false)
                .put("downloadDiagnostics",new JSONObject(attempt.diagnostics))
                .put("recoveryAction","restart_shizuku_then_refresh");
            boolean exact=false,nativeSuccess=attempt.result!=null,unchanged=false;
            try {
                FiveberReaderSelector.Target afterCard=FiveberReaderSelector.discover();
                if(!plan.reader.equals(afterCard.reader) || !plan.hash.equals(afterCard.identityHash) || !plan.identityHash.equals(afterCard.identityHash))
                    throw new SecurityException("Card changed after download");
                // Independent native + Java inventory readback, not command status alone.
                PhoneNativeLpac.listProfiles();
                List<DownloadGuard.Row> after=rows(CardProfileInventory.list());
                unchanged=plan.fingerprint.equals(DownloadGuard.fingerprint(after));
                if(nativeSuccess)exact=DownloadGuard.exactInstalled(before,after,attempt.result.getString("installedIccid"));
            } catch(Exception ignored) {
                // Cannot conclude failure, absence or installation from a failed reconciliation.
            }
            JSONObject response = new JSONObject().put("ok",exact).put("status",exact?"installed_disabled":"unknown")
                .put("verified",exact).put("installed",exact?Boolean.TRUE:JSONObject.NULL)
                .put("enabled",exact?Boolean.FALSE:JSONObject.NULL).put("nativeSuccess",nativeSuccess)
                .put("reviewRequired",!exact).put("inventoryUnchanged",unchanged)
                .put("cardReadRequired",!exact).put("workerStopped",true).put("retryAllowed",false)
                .put("downloadDiagnostics",new JSONObject(attempt.diagnostics));
            if(exact) {
                JSONObject delivery=PhoneNotificationDelivery.afterInstall(plan.reader,plan.hash,plan.activation.host,
                    attempt.result.getLong("notificationSequence"),attempt.result.getString("installedIccid"));
                for(String key:new String[]{"notificationStatus","notificationReviewRequired","notificationPendingCount","notificationCleanupAvailable","notificationWorkerStopped","recoveryAction"})
                    if(delivery.has(key))response.put(key,delivery.get(key));
                safeToUnlock=delivery.optBoolean("notificationWorkerStopped",true);
            }
            return response;
        } finally {
            // A stuck HAL worker quarantines further operations until the service is restarted.
            if(safeToUnlock)CardTransactionLock.release();
        }
    }
    private PortableProfileDownload(){}
}
