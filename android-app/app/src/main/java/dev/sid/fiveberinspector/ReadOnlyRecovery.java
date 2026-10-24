package dev.sid.fiveberinspector;

import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.nio.channels.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Recover observation, never a mutation grant or a disposition of the unknown operation. */
final class ReadOnlyRecovery {
 private static final Path ROOT=Paths.get("/data/local/tmp/fiveber-lpac-lab");
 private static final Path MARKER=ROOT.resolve("card-transaction.lock.quarantine");
 static final class Failure extends SecurityException {
  final String code;
  Failure(String code){super(code);this.code=code;}
 }
 private static final RecoveryProcessGuard.Host HOST=new RecoveryProcessGuard.Host(){
  public List<RecoveryProcessGuard.Worker> snapshot()throws Exception{
   Process p=new ProcessBuilder("/system/bin/ps","-A","-o","UID,PID,PPID,NAME").redirectError(new File("/dev/null")).start();
   List<String[]> table=new ArrayList<>();
   try(BufferedReader in=new BufferedReader(new InputStreamReader(p.getInputStream(),StandardCharsets.US_ASCII))){
    String line;int count=0;while((line=in.readLine())!=null){
     if(++count>8192||line.length()>512)throw new IOException("Process inventory invalid");
     String[] fields=RecoveryProcessGuard.processRow(line);
     if(!fields[0].equals("UID"))table.add(fields);
    }
   }finally{if(!p.waitFor(5,TimeUnit.SECONDS)){p.destroyForcibly();throw new IOException("Process inventory unavailable");}}
   if(p.exitValue()!=0)throw new IOException("Process inventory unavailable");
   List<RecoveryProcessGuard.Worker> result=new ArrayList<>();
   for(String[] f:table){
    int uid=Integer.parseInt(f[0]),pid=Integer.parseInt(f[1]),parent=Integer.parseInt(f[2]);String name=f[3];
    String exe="",cp="",start="";
    if(uid==2000&&(name.equals(RecoveryProcessGuard.NAME)||name.equals("lpac"))){
     Path proc=Paths.get("/proc",Integer.toString(pid));
     try{
      exe=Files.readSymbolicLink(proc.resolve("exe")).toString();
      String stat=new String(Files.readAllBytes(proc.resolve("stat")),StandardCharsets.US_ASCII);
      start=stat.substring(stat.lastIndexOf(')')+2).split(" ")[19];
      if(name.equals(RecoveryProcessGuard.NAME)){
       byte[] command=Files.readAllBytes(proc.resolve("cmdline"));
       if(!new String(command,StandardCharsets.US_ASCII).replace("\u0000", "").trim().equals(RecoveryProcessGuard.NAME))throw new IOException("Process identity unavailable");
       byte[] environment=Files.readAllBytes(proc.resolve("environ"));if(environment.length>131072)throw new IOException("Process identity unavailable");
       for(String entry:new String(environment,StandardCharsets.UTF_8).split("\u0000"))if(entry.startsWith("CLASSPATH=")){if(!cp.isEmpty())throw new IOException("Process identity ambiguous");cp=entry.substring(10);}
      }
     }catch(NoSuchFileException gone){continue;}
    }
    result.add(new RecoveryProcessGuard.Worker(pid,uid,parent,name,exe,cp,start));
   }
   return result;
  }
  public void terminate(RecoveryProcessGuard.Worker expected)throws Exception{
   expected.validate();RecoveryProcessGuard.Worker current=null;
   for(RecoveryProcessGuard.Worker w:snapshot())if(w.pid==expected.pid)current=w;
   if(!expected.same(current))throw new SecurityException("RECOVERY_IDENTITY_UNVERIFIED");
   android.os.Process.sendSignal(expected.pid,15);
  }
  public void pause()throws Exception{Thread.sleep(250);}
 };
 static void beforeConnect()throws Exception{
  if(!CardTransactionLock.persistentQuarantine())return;
  if(android.os.Process.myUid()!=2000)throw new Failure("RECOVERY_IDENTITY_UNVERIFIED");
  if(!ROOT.toFile().getCanonicalFile().equals(ROOT.toFile()))throw new Failure("RECOVERY_EVIDENCE_INVALID");
  CardTransactionLock.validateMarker(MARKER);
  // Serialize simultaneous ADB recovery attempts. Keep this lock file; never unlink a lock inode.
  Path guard=ROOT.resolve("read-only-recovery.lock");
  if(Files.isSymbolicLink(guard))throw new Failure("RECOVERY_IDENTITY_UNVERIFIED");
  try(FileChannel channel=FileChannel.open(guard,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
      FileLock lock=channel.tryLock()){
   if(lock==null)throw new Failure("RECOVERY_WORKER_ACTIVE");
   Files.setPosixFilePermissions(guard,PosixFilePermissions.fromString("rw-------"));
   Properties journal=new OperationJournal(new File("/data/local/tmp/fiveber-rescue-state")).read();
   if(!journal.getProperty("status","").matches("unknown|observed_unknown|running"))throw new Failure("RECOVERY_EVIDENCE_INVALID");
   String marker=NativeLpacBundle.digest(MARKER.toFile()),record=OperationJournal.recordDigest(journal);
   int retired=RecoveryProcessGuard.retire(HOST,android.os.Process.myPid());
   CardTransactionLock.acquireReadOnly();
   try{receipt("backend_retired_read_only",marker,record,"", "",retired);}
   finally{CardTransactionLock.release();}
  }
 }
 static void acquire()throws Exception{
  boolean isolated=CardTransactionLock.persistentQuarantine();
  try{CardTransactionLock.acquireReadOnly();}
  catch(SecurityException failure){if(isolated)throw new Failure("RECOVERY_WORKER_ACTIVE");throw failure;}
  try{if(isolated){CardTransactionLock.validateMarker(MARKER);RecoveryProcessGuard.quiescent(HOST.snapshot(),android.os.Process.myPid());}}
  catch(Exception failure){CardTransactionLock.release();throw new Failure("RECOVERY_WORKER_ACTIVE");}
 }
 static void observed(String card,String fingerprint)throws Exception{
  if(!CardTransactionLock.persistentQuarantine())return;
  Properties journal=new OperationJournal(new File("/data/local/tmp/fiveber-rescue-state")).read();
  if(!card.equals(journal.getProperty("card")))throw new Failure("RECOVERY_CARD_CHANGED");
  CardTransactionLock.validateMarker(MARKER);
  receipt("stable_inventory_read_only",NativeLpacBundle.digest(MARKER.toFile()),OperationJournal.recordDigest(journal),card,fingerprint,0);
 }
 private static void receipt(String phase,String marker,String journal,String card,String inventory,int retired)throws Exception{
  Path directory=ROOT.resolve("read-only-recovery");
  if(Files.isSymbolicLink(directory))throw new Failure("RECOVERY_EVIDENCE_INVALID");
  Files.createDirectories(directory);Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("rwx------"));
  Properties proof=new Properties();proof.setProperty("schema","1");proof.setProperty("phase",phase);proof.setProperty("markerDigest",marker);
  proof.setProperty("journalDigest",journal);proof.setProperty("cardDigest",card);proof.setProperty("inventoryDigest",inventory);
  proof.setProperty("retiredBackends",Integer.toString(retired));proof.setProperty("observedAt",Long.toString(System.currentTimeMillis()));
  proof.setProperty("apkDigest",NativeLpacBundle.digest(new File(NativeLpacBundle.installedApk())));proof.setProperty("writesAuthorized","false");
  Path path=directory.resolve(UUID.randomUUID()+".properties");
  try(FileChannel file=FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
   Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"));
   ByteArrayOutputStream bytes=new ByteArrayOutputStream();proof.store(bytes,"Read-only recovery evidence; original unknown outcome retained");
   java.nio.ByteBuffer buffer=java.nio.ByteBuffer.wrap(bytes.toByteArray());while(buffer.hasRemaining())file.write(buffer);file.force(true);
  }
  try(FileChannel parent=FileChannel.open(directory,StandardOpenOption.READ)){parent.force(true);}
 }
 /** No journal disposition may be persisted before this worker and lock check. */
 static void requireReviewQuiescent()throws Exception{
  if(!CardTransactionLock.ownedByCurrentThread()||CardTransactionLock.isQuarantined())
   throw new Failure("RECOVERY_WORKER_ACTIVE");
  RecoveryProcessGuard.quiescent(HOST.snapshot(),android.os.Process.myPid());
  Thread.sleep(200);
  RecoveryProcessGuard.quiescent(HOST.snapshot(),android.os.Process.myPid());
 }
 /** Archive the original quarantine only during a deliberate, authenticated lifecycle review.
  * Must be called while ReadOnlyRecovery.acquire() owns the original file lock. */
 static void archiveAfterReview(String card,String journalDigest,String snapshot,String authorization)throws Exception{
  if(!CardTransactionLock.ownedByCurrentThread()||CardTransactionLock.isQuarantined())
   throw new Failure("RECOVERY_WORKER_ACTIVE");
  OperationJournal journal=new OperationJournal(new File("/data/local/tmp/fiveber-rescue-state"));
  Properties operation=journal.read();
  if(!card.equals(operation.getProperty("card"))||!journalDigest.equals(OperationJournal.recordDigest(operation))
    ||!snapshot.equals(journal.reviewedLifecycleSnapshot()))
   throw new Failure("RECOVERY_EVIDENCE_INVALID");
  RecoveryProcessGuard.quiescent(HOST.snapshot(),android.os.Process.myPid());
  Thread.sleep(200);
  RecoveryProcessGuard.quiescent(HOST.snapshot(),android.os.Process.myPid());
  QuarantineReview.archive(ROOT.resolve("card-transaction.lock"),card,journalDigest,snapshot,
      authorization,NativeLpacBundle.digest(new File(NativeLpacBundle.installedApk())));
 }
 static String code(Throwable cause){
  for(int i=0;cause!=null&&i<12;i++,cause=cause.getCause())if(cause instanceof Failure)return ((Failure)cause).code;
  return "";
 }
 private ReadOnlyRecovery(){}
}