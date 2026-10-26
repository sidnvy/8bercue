package dev.sid.fiveberinspector;

import java.nio.file.*;
import java.util.*;

/** Production policy and real journal I/O; no simulated result qualifies hardware. */
public final class DownloadRecoveryOfflineTest {
    static int checks;
    static final String A="a".repeat(64), B="b".repeat(64), SNAP="c".repeat(64);
    static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    static Properties refusal(){
        DownloadDiagnostics d=new DownloadDiagnostics();
        d.nativeStarted();d.http("authenticateClient");d.httpStatus(200);
        d.server("Failed","8.2.6","3.8");d.nativeResult(-1,"es9p_authenticate_client");
        d.installCommands(0);d.nativeStopped(true);
        return d.snapshot(false,true);
    }
    static boolean known(Properties p){return DownloadOutcomePolicy.notInstalled(false,true,true,p);}
    static void store(Path path,Properties p)throws Exception{try(var out=Files.newOutputStream(path)){p.store(out,"Synthetic fixture");}}
    public static void main(String[] args)throws Exception{
        Properties p=refusal();check(known(p),"Terminal authentication refusal before any install is a known non-installation");
        check(!DownloadOutcomePolicy.notInstalled(true,true,true,p),"Native success cannot be reclassified as a refusal");
        check(!DownloadOutcomePolicy.notInstalled(false,false,true,p),"Unstopped worker remains unknown");
        check(!DownloadOutcomePolicy.notInstalled(false,true,false,p),"Changed or unreadable inventory remains unknown");
        check(!known(null),"Missing diagnostics remains unknown");
        for(String key:p.stringPropertyNames()){
            if(!key.matches("downloadPhase|downloadFailure|downloadServerStatus|downloadHttpStatus|downloadNativeCode|downloadInstallCommands|downloadWorkerStopped|downloadNativeStopped"))continue;
            Properties missing=(Properties)p.clone();missing.remove(key);check(!known(missing),"Evidence is mandatory: "+key);
        }
        for(String phase:List.of("BOUND_PROFILE_PACKAGE","CARD_LOAD_PACKAGE","CARD_PREPARE_DOWNLOAD","CANCEL_SESSION","NATIVE_EXIT","LAUNCH")){
            Properties changed=(Properties)p.clone();changed.setProperty("downloadPhase",phase);check(!known(changed),"Later or unproven phase remains unknown: "+phase);
        }
        for(String count:List.of("1","18000","not_reported","-1","")){
            Properties changed=(Properties)p.clone();changed.setProperty("downloadInstallCommands",count);check(!known(changed),"Cannot exclude package transfer: "+count);
        }
        for(String kind:List.of("TLS","DNS","NETWORK_TIMEOUT","PROTOCOL","POLICY","IO","NATIVE_REJECTED")){
            Properties changed=(Properties)p.clone();changed.setProperty("downloadFailure",kind);check(!known(changed),"Uncertain failure is not an operator refusal: "+kind);
        }
        for(String field:List.of("downloadNativeStopped","downloadWorkerStopped")){
            Properties changed=(Properties)p.clone();changed.setProperty(field,"false");check(!known(changed),"Both processes must stop: "+field);
        }
        Properties changed=(Properties)p.clone();changed.setProperty("downloadHttpStatus","500");check(!known(changed),"HTTP error is not a validated application-level refusal");
        changed=(Properties)p.clone();changed.setProperty("downloadServerStatus","Executed-Success");check(!known(changed),"Success cannot authorize a refusal outcome");
        changed=(Properties)p.clone();changed.setProperty("downloadNativeCode","0");check(!known(changed),"Native success conflicts with refusal");
        DownloadDiagnostics firstFailure=new DownloadDiagnostics();firstFailure.http("authenticateClient");firstFailure.server("Failed","8.2.6","3.8");
        firstFailure.http("cancelSession");firstFailure.phase("NATIVE_EXIT");
        check("AUTHENTICATE_CLIENT".equals(firstFailure.snapshot(false,true).getProperty("downloadPhase")),"Cancellation cannot hide the original refusal stage");
        check(!known(firstFailure.snapshot(false,true)),"Missing package-command observation is not assumed zero");

        Path root=Files.createTempDirectory("download-recovery-");
        OperationJournal legacy=new OperationJournal(root.toFile());legacy.begin(new OperationJournal.Binding("enable",A,A,A,A));legacy.finish("unknown");
        byte[] original=Files.readAllBytes(root.resolve("operation.properties"));
        OperationJournal card=OperationJournal.forCard(root.toFile(),B);
        card.begin(new OperationJournal.Binding("download",B,B,SNAP,B));card.finish("not_completed",p);
        check(!card.requiresReconciliation(),"A proven pre-install refusal does not pause further card management");
        check("not_completed".equals(card.statusAfterReadOnlyReconciliation()),"Refusal is not reported as successful installation");
        check(OperationJournal.forCard(root.toFile(),A).requiresReconciliation(),"Another card's unknown operation stays unresolved");
        card.begin(new OperationJournal.Binding("download",B,B,SNAP,B));card.finish("unknown");
        Properties record=card.read();String tx=record.getProperty("txId");
        Path directory=root.resolve("cards").resolve(B),history=directory.resolve("history").resolve(tx+".properties");
        byte[] current=Files.readAllBytes(directory.resolve("operation.properties")),past=Files.readAllBytes(history);
        check(card.canReviewUnchangedDownload(B,SNAP),"Unknown download can be explicitly abandoned on the unchanged original card");
        check(!card.canReviewUnchangedDownload(A,SNAP),"A different card cannot review the download");
        check(!card.canReviewUnchangedDownload(B,A),"Changed inventory cannot be reviewed as an unchanged download");
        check(!card.canReviewObservedLifecycle(B),"Download never enters lifecycle acceptance");
        card.abandonUnchangedDownload(tx,B,SNAP,OperationJournal.recordDigest(record),A);
        check(!card.requiresReconciliation(),"An explicit, durable unchanged-download review permits new actions");
        check("reviewed_abandoned".equals(card.statusAfterReadOnlyReconciliation()),"Original remote result remains unknown and abandoned");
        check(!card.canReviewUnchangedDownload(B,SNAP),"A completed review is not replayable");
        check(Arrays.equals(current,Files.readAllBytes(directory.resolve("operation.properties"))),"Review does not rewrite original outcome bytes");
        check(Arrays.equals(past,Files.readAllBytes(history)),"Original transaction history is preserved");
        check(Arrays.equals(original,Files.readAllBytes(root.resolve("operation.properties"))),"Legacy card evidence is untouched");
        check(OperationJournal.forCard(root.toFile(),A).requiresReconciliation(),"Review is isolated to its full card identity");
        card.begin(new OperationJournal.Binding("download",B,A,SNAP,B));card.finish("unknown");
        record=card.read();tx=record.getProperty("txId");
        Path fresh=directory.resolve("history").resolve(tx+".properties");
        Files.delete(fresh);check(!card.canReviewUnchangedDownload(B,SNAP),"Missing original history cannot offer an impossible review");
        store(fresh,record);Files.writeString(directory.resolve("history/interrupted.pending"),"incomplete");
        check(!card.canReviewUnchangedDownload(B,SNAP),"Incomplete durable commit prevents download review");
        System.out.println("PASS "+checks+" actual download outcome and durable recovery assertions (offline I/O)");
    }
}
