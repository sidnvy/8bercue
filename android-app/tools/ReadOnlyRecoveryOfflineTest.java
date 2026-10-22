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
  denied(()->journal.closeUnstartedDownload(hash,hash),"an unknown enable is not an unstarted download");
  OperationJournal download=new OperationJournal(dir.resolve("download-journal").toFile());
  download.begin(new OperationJournal.Binding("download",hash,hash,hash,hash));
  Properties tls=new Properties();
  tls.setProperty("downloadPhase","INITIATE_AUTHENTICATION");tls.setProperty("downloadFailure","TLS");
  tls.setProperty("downloadWorkerStopped","true");tls.setProperty("downloadNativeStopped","true");
  tls.setProperty("downloadHttpStatus","0");tls.setProperty("downloadServerStatus","not_reported");
  tls.setProperty("downloadSubjectCode","not_reported");tls.setProperty("downloadReasonCode","not_reported");
  tls.setProperty("downloadNativeCode","not_reported");
  download.finish("unknown",tls);
  check(download.requiresReconciliation(),"tls download stays blocked until the same inventory is confirmed");
  denied(()->download.closeUnstartedDownload(hash,String.join("",Collections.nCopies(64,"b"))),"different inventory stays blocked");
  download.closeUnstartedDownload(hash,hash);
  check(!download.requiresReconciliation() && "not_completed".equals(download.statusAfterReadOnlyReconciliation()),"unstarted tls download closes");
  OperationJournal loaded=new OperationJournal(dir.resolve("loaded-journal").toFile());
  loaded.begin(new OperationJournal.Binding("download",hash,hash,hash,hash));
  Properties cardWrite=new Properties();cardWrite.putAll(tls);cardWrite.setProperty("downloadPhase","CARD_LOAD_PACKAGE");
  loaded.finish("unknown",cardWrite);
  denied(()->loaded.closeUnstartedDownload(hash,hash),"a download that reached the card stays unknown");
  check(loaded.requiresReconciliation(),"card-touching download remains blocked");
  System.out.println("PASS "+checks+" read-only recovery, worker identity, process exclusion and unknown-outcome assertions");
 }
}
