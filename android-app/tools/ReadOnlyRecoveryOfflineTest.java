package dev.sid.fiveberinspector;
import java.nio.file.*;
import java.util.*;
import java.io.*;
public final class ReadOnlyRecoveryOfflineTest {
 static int checks;
 static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
 interface Task{void run()throws Exception;}
 static void denied(Task t,String s)throws Exception{boolean refused=false;try{t.run();}catch(SecurityException|IOException expected){refused=true;}check(refused,s);}
 static RecoveryProcessGuard.Worker worker(int pid,int uid,int parent,String name,String exe,String cp,String start){return new RecoveryProcessGuard.Worker(pid,uid,parent,name,exe,cp,start);}
 static final String APK="/data/app/~~test==/dev.sid.fiveberinspector-example==/base.apk";
 static RecoveryProcessGuard.Worker peer(int pid){return worker(pid,2000,1,RecoveryProcessGuard.NAME,"/system/bin/app_process64",APK,"12345");}
 static class Host implements RecoveryProcessGuard.Host{
  List<RecoveryProcessGuard.Worker> rows=new ArrayList<>();List<Integer> signals=new ArrayList<>();boolean change,stuck;int scans;
  public List<RecoveryProcessGuard.Worker> snapshot(){scans++;if(change&&scans==2){rows=new ArrayList<>(Arrays.asList(worker(20,2000,1,RecoveryProcessGuard.NAME,"/system/bin/app_process64",APK,"99999")));}return new ArrayList<>(rows);}
  public void terminate(RecoveryProcessGuard.Worker w){signals.add(w.pid);if(!stuck)rows.remove(w);}
  public void pause(){}
 }
 public static void main(String[]args)throws Exception{
  if(args.length==2&&args[0].equals("hold")){
   CardTransactionLock.acquire(new File(args[1]),true);System.out.println("HELD");System.out.flush();System.in.read();CardTransactionLock.release();return;
  }
  Host h=new Host();h.rows.add(peer(20));check(RecoveryProcessGuard.retire(h,99)==1&&h.signals.equals(Arrays.asList(20)),"only exact old backend retired");
  check(RecoveryProcessGuard.processRow("    0   824     2 [irq/234-q6v5 wdog]")[3].equals("[irq/234-q6v5 wdog]"),"Moto kernel names may contain spaces");
  check(RecoveryProcessGuard.processRow("  UID   PID  PPID NAME  ")[0].equals("UID"),"ps header accepted");
  denied(()->RecoveryProcessGuard.processRow("2000 broken 1 8bercue-management-v1"),"invalid pid refused");
  denied(()->RecoveryProcessGuard.processRow("2000 1"),"incomplete process row refused");
  h=new Host();h.rows.add(peer(99));check(RecoveryProcessGuard.retire(h,99)==0&&h.signals.isEmpty(),"own backend excluded");
  h=new Host();h.rows.add(peer(20));h.rows.add(worker(21,2000,20,"lpac","/data/local/tmp/fiveber-lpac-lab/lpac","",""));Host nativeBusy=h;
  denied(()->RecoveryProcessGuard.retire(nativeBusy,99),"native worker blocks retirement");check(h.signals.isEmpty(),"no native signal");
  h=new Host();h.rows.add(peer(20));h.rows.add(worker(21,2000,20,"other","","",""));Host child=h;
  denied(()->RecoveryProcessGuard.retire(child,99),"any child blocks retirement");check(h.signals.isEmpty(),"no partial retirement");
  h=new Host();h.rows.add(worker(20,2001,1,RecoveryProcessGuard.NAME,"/system/bin/app_process64",APK,"123"));Host wrongUid=h;
  denied(()->RecoveryProcessGuard.retire(wrongUid,99),"same name other uid never signaled");check(h.signals.isEmpty(),"other uid preserved");
  for(String[] bad:new String[][]{{"/bin/other",APK},{"/system/bin/app_process64","/data/local/tmp/app.apk"},{"/system/bin/app_process64",APK+":/tmp/x.jar"}}){
   h=new Host();h.rows.add(worker(20,2000,1,RecoveryProcessGuard.NAME,bad[0],bad[1],"123"));Host invalid=h;
   denied(()->RecoveryProcessGuard.retire(invalid,99),"unverified executable/classpath refused");check(h.signals.isEmpty(),"invalid process not signaled");
  }
  h=new Host();h.rows.add(peer(20));h.change=true;Host reused=h;denied(()->RecoveryProcessGuard.retire(reused,99),"reused pid/starttime refused");check(h.signals.isEmpty(),"reused process untouched");
  h=new Host();h.rows.add(peer(20));h.stuck=true;Host stuck=h;denied(()->RecoveryProcessGuard.retire(stuck,99),"termination must be observed");
  Host empty=new Host();check(RecoveryProcessGuard.retire(empty,99)==0,"empty worker set accepted");
  Path dir=Files.createTempDirectory("8bercue-recovery-");File file=dir.resolve("card-transaction.lock").toFile();Path marker=dir.resolve("card-transaction.lock.quarantine");
  byte[] original="worker_termination_unconfirmed\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);Files.write(marker,original);
  denied(()->CardTransactionLock.acquire(file),"persistent marker blocks writes");
  CardTransactionLock.acquire(file,true);check(CardTransactionLock.isActive(),"read still holds exclusion");CardTransactionLock.release();
  check(Arrays.equals(original,Files.readAllBytes(marker)),"read never deletes or edits marker");
  denied(()->CardTransactionLock.acquire(file),"read grants no future write");
  Process childLock=new ProcessBuilder(System.getProperty("java.home")+"/bin/java","-cp",System.getProperty("java.class.path"),ReadOnlyRecoveryOfflineTest.class.getName(),"hold",file.toString()).start();
  check("HELD".equals(new BufferedReader(new InputStreamReader(childLock.getInputStream())).readLine()),"independent process acquired lock");
  denied(()->CardTransactionLock.acquire(file,true),"recovery cannot bypass a live kernel lock");childLock.getOutputStream().write(1);childLock.getOutputStream().flush();check(childLock.waitFor()==0,"lock holder exited normally");
  CardTransactionLock.acquire(file,true);CardTransactionLock.release();check(Arrays.equals(original,Files.readAllBytes(marker)),"exclusion inode and marker retained");
  Files.write(marker,"unexpected".getBytes());denied(()->CardTransactionLock.acquire(file,true),"malformed marker refused");
  Files.delete(marker);Files.createSymbolicLink(marker,dir.resolve("other"));denied(()->CardTransactionLock.acquire(file,true),"symlink marker refused");Files.delete(marker);
  OperationJournal journal=new OperationJournal(dir.resolve("journal").toFile());String hash=String.join("",Collections.nCopies(64,"a"));
  journal.begin(new OperationJournal.Binding("enable",hash,hash,hash,hash));journal.finish("unknown");
  byte[] before=Files.readAllBytes(dir.resolve("journal/operation.properties"));Files.write(marker,original);
  CardTransactionLock.acquire(file,true);check(journal.requiresReconciliation(),"unknown transaction remains blocked during read");CardTransactionLock.release();
  check(Arrays.equals(before,Files.readAllBytes(dir.resolve("journal/operation.properties"))),"observation preserves original transaction bytes");
  denied(()->journal.begin(new OperationJournal.Binding("delete",hash,hash,hash,hash)),"read cannot authorize new delete");
  // A confirmed card read is NEVER sufficient to unlock a previous unknown enable.
  String tx=journal.read().getProperty("txId"),digest=OperationJournal.recordDigest(journal.read());
  String alternate="b".repeat(64),authorization="c".repeat(64);
  denied(()->journal.reviewObservedLifecycle(tx,alternate,hash,digest,authorization),"different card cannot be reviewed");
  denied(()->journal.reviewObservedLifecycle(tx,hash,hash,alternate,authorization),"stale record digest refused");
  denied(()->journal.reviewObservedLifecycle(tx,hash,hash,digest,"bad"),"missing explicit consent refused");
  denied(()->QuarantineReview.archive(file.toPath(),hash,digest,hash,authorization,hash),"no lock cannot archive quarantine");
  check(journal.requiresReconciliation(),"refused reviews keep write block");
  journal.reviewObservedLifecycle(tx,hash,hash,digest,authorization);
  check("reviewed_observed".equals(journal.statusAfterReadOnlyReconciliation()),"review is durable before marker archival");
  check(hash.equals(journal.reviewedLifecycleSnapshot()),"snapshot bound to immutable review receipt");
  denied(()->CardTransactionLock.acquire(file),"worker quarantine still blocks writes after durable review");
  // Simulate a crash between disposition fsync and quarantine retirement:
  // a restarted caller can finish the marker archival without touching the unknown journal.
  CardTransactionLock.acquire(file,true);
  check(CardTransactionLock.ownedByCurrentThread(),"review holds the original kernel lock");
  check(QuarantineReview.archive(file.toPath(),hash,digest,hash,authorization,hash),"explicit review archives marker under lock");
  CardTransactionLock.release();
  check(!Files.exists(marker),"active quarantine path cleared only after archival");
  File[] archives=dir.toFile().listFiles((d,n)->n.startsWith("card-transaction.lock.quarantine.reviewed."));
  check(archives!=null&&archives.length==1&&Arrays.equals(original,Files.readAllBytes(archives[0].toPath())),"quarantine evidence bytes preserved");
  check(Files.list(dir.resolve("reviewed-quarantines")).count()==1,"durable authorization receipt retained");
  check(Arrays.equals(before,Files.readAllBytes(dir.resolve("journal/operation.properties"))),"original uncertain transaction unchanged after review and archival");
  check(!journal.requiresReconciliation(),"explicit review resolves only that lifecycle block");
  check("reviewed_observed".equals(journal.statusAfterReadOnlyReconciliation()),"outcome represented as reviewed, not verified");
  check("unknown".equals(journal.read().getProperty("status")),"original unknown outcome never rewritten");
  check(Arrays.equals(before,Files.readAllBytes(dir.resolve("journal/operation.properties"))),"original journal bytes retained after review");
  denied(()->journal.reviewObservedLifecycle(tx,hash,hash,digest,authorization),"review receipt immutable and not replayable");
  // New writes must still acquire normal exclusion and write a NEW journal.
  CardTransactionLock.acquire(file);
  try{journal.begin(new OperationJournal.Binding("disable",hash,hash,hash,hash));journal.finish("verified");}
  finally{CardTransactionLock.release();}
  check("verified".equals(journal.read().getProperty("status")),"subsequent independent operation starts normally");
  // Issue #14 reproduction: no quarantine marker exists but unknown enable history
  // alone deadlocks further writes, even though the kernel card lock is free.
  Path noMarker=dir.resolve("no-marker");
  Files.createDirectories(noMarker);
  OperationJournal noMarkerJournal=new OperationJournal(noMarker.resolve("journal").toFile());
  noMarkerJournal.begin(new OperationJournal.Binding("enable",hash,hash,hash,hash));
  noMarkerJournal.finish("unknown");
  File cleanLock=noMarker.resolve("card-transaction.lock").toFile();
  CardTransactionLock.acquire(cleanLock);
  CardTransactionLock.release();
  check(!Files.exists(noMarker.resolve("card-transaction.lock.quarantine")),"issue 14 has no marker");
  denied(()->noMarkerJournal.begin(new OperationJournal.Binding("enable",hash,hash,hash,hash)),
      "unknown journal alone blocks writes even without marker");
  String ntx=noMarkerJournal.read().getProperty("txId");
  String ndig=OperationJournal.recordDigest(noMarkerJournal.read());
  denied(()->noMarkerJournal.reviewObservedLifecycle(ntx,alternate,hash,ndig,authorization),
      "issue 14 still requires same card and cannot use another identity");
  // RecoveryProcessGuard rejects a stray native card worker in this marker-free case.
  denied(()->RecoveryProcessGuard.quiescent(Arrays.asList(worker(44,2000,1,"lpac",
      "/data/local/tmp/fiveber-lpac-lab/lpac","","")) ,99),
      "no marker does not excuse a running native worker");
  RecoveryProcessGuard.quiescent(Collections.emptyList(),99);
  CardTransactionLock.acquire(cleanLock,true);
  try{
    noMarkerJournal.reviewObservedLifecycle(ntx,hash,hash,ndig,authorization);
  }finally{CardTransactionLock.release();}
  check(!noMarkerJournal.requiresReconciliation(),"issue 14 explicit reviewed history restores write authorization");
  check("unknown".equals(noMarkerJournal.read().getProperty("status")),
      "issue 14 recovery does not rewrite the unknown result");
  check(hash.equals(noMarkerJournal.reviewedLifecycleSnapshot()),"reviewed inventory is durably bound");
  // Fail-closed journal history edge cases reported in review.
  for(String mode:new String[]{"pending","other-unknown","history-mismatch","changed-digest","running"}) {
    Path scenario=Files.createTempDirectory("8bercue-negative-"+mode);
    OperationJournal subject=new OperationJournal(scenario.toFile());
    subject.begin(new OperationJournal.Binding("enable",hash,hash,hash,hash));
    if(!mode.equals("running"))subject.finish("unknown");
    String tid=subject.read().getProperty("txId");
    String originalDigest=OperationJournal.recordDigest(subject.read());
    Path historyFile=scenario.resolve("history").resolve(tid+".properties");
    switch(mode){
      case "pending":
        Files.writeString(scenario.resolve("history").resolve("interrupted.pending"),"incomplete");
        denied(()->subject.reviewObservedLifecycle(tid,hash,hash,originalDigest,authorization),"pending history cannot be reviewed");
        break;
      case "other-unknown":
        Properties other=subject.read();
        String otherTx=java.util.UUID.randomUUID().toString();
        other.setProperty("txId",otherTx);
        try(OutputStream out=Files.newOutputStream(scenario.resolve("history").resolve(otherTx+".properties"))){other.store(out,"synthetic");}
        denied(()->subject.reviewObservedLifecycle(tid,hash,hash,originalDigest,authorization),"another unknown transaction blocks review");
        break;
      case "history-mismatch":
        Properties mismatched=subject.read();
        mismatched.setProperty("startedAt","1");
        try(OutputStream out=Files.newOutputStream(historyFile)){mismatched.store(out,"synthetic");}
        denied(()->subject.reviewObservedLifecycle(tid,hash,hash,originalDigest,authorization),"inconsistent history cannot authorize review");
        break;
      case "changed-digest":
        subject.observe(hash,hash);
        denied(()->subject.reviewObservedLifecycle(tid,hash,hash,originalDigest,authorization),"observe changes digest and invalidates proposal");
        break;
      case "running":
        // Match the production caller contract: same original kernel lock and
        // verified absence of native/peer workers BEFORE creating a receipt.
        File scenarioLock=scenario.resolve("card-transaction.lock").toFile();
        CardTransactionLock.acquire(scenarioLock,true);
        try {
          RecoveryProcessGuard.quiescent(Collections.emptyList(),99);
          subject.reviewObservedLifecycle(tid,hash,hash,originalDigest,authorization);
        }finally{CardTransactionLock.release();}
        check("reviewed_observed".equals(subject.statusAfterReadOnlyReconciliation()),"running transaction requires explicit reviewed disposition");
        check("running".equals(subject.read().getProperty("status")),"running original status remains unchanged");
    }
    if(!mode.equals("running"))
      check(!Files.exists(scenario.resolve("resolutions").resolve(tid+".properties")),
          "rejected "+mode+" cannot leave a review authorization receipt");
  }
  // Uncertain remote downloads and irreversible deletion cannot use this lifecycle shortcut.
  OperationJournal download=new OperationJournal(dir.resolve("download").toFile());
  download.begin(new OperationJournal.Binding("download",hash,hash,hash,hash));download.finish("unknown");
  String dt=download.read().getProperty("txId");
  denied(()->download.reviewObservedLifecycle(dt,hash,hash,OperationJournal.recordDigest(download.read()),authorization),"download cannot be accepted as lifecycle");
  OperationJournal deletion=new OperationJournal(dir.resolve("deletion").toFile());
  deletion.begin(new OperationJournal.Binding("delete",hash,hash,hash,hash));deletion.finish("unknown");
  String dtx=deletion.read().getProperty("txId");
  denied(()->deletion.reviewObservedLifecycle(dtx,hash,hash,OperationJournal.recordDigest(deletion.read()),authorization),"delete cannot be accepted as lifecycle");
  byte[] valid=Files.readAllBytes(dir.resolve("journal/resolutions").resolve(tx+".properties"));
  Files.writeString(dir.resolve("journal/resolutions").resolve(tx+".properties"),"not an authenticated receipt");
  denied(()->journal.requiresReconciliation(),"tampered receipt fails closed");
  Files.write(dir.resolve("journal/resolutions").resolve(tx+".properties"),valid);
  check(!journal.requiresReconciliation(),"valid saved review restores status");
  System.out.println("PASS "+checks+" read-only recovery, worker identity, process exclusion and unknown-outcome assertions");
 }
}