package dev.sid.fiveberinspector;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;

/** Same-process and cross-process exclusion for local Binder and the legacy shell companion. */
public final class CardTransactionLock {
 private static final File ROOT=new File("/data/local/tmp/fiveber-lpac-lab");
 private static RandomAccessFile handle;
 private static FileLock lock;
 private static Thread owner;
 private static boolean quarantined;
 private static boolean coordinated;
 private static int depth;
 private static Path quarantineFile;
 private CardTransactionLock(){}
 public static synchronized void acquire(){acquire(new File(ROOT,"card-transaction.lock"),false);}
 /** Observation-only entry. The persistent write block is NEVER removed. */
 static synchronized void acquireReadOnly(){acquire(new File(ROOT,"card-transaction.lock"),true);}
 // Package-private injection permits hermetic process-exclusion tests without Android paths.
 static synchronized void acquire(File file){acquire(file,false);}
 static synchronized void acquire(File file,boolean readOnly){
  if(quarantined)throw new SecurityException("Worker termination unresolved");
  if(owner!=null){
   if(coordinated && owner==Thread.currentThread()){depth++;return;}
   throw new SecurityException("Another card transaction is underway");
  }
  RandomAccessFile opened=null;
  try{
   File parent=file.getParentFile();
   if(!parent.getCanonicalFile().equals(parent.getAbsoluteFile())||Files.isSymbolicLink(file.toPath()))throw new SecurityException("Unsafe transaction lock path");
   if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("Lock directory unavailable");
   Files.setPosixFilePermissions(parent.toPath(),PosixFilePermissions.fromString("rwx------"));
   Path marker=file.toPath().resolveSibling(file.getName()+".quarantine");
   if(Files.exists(marker,LinkOption.NOFOLLOW_LINKS)){
    if(!readOnly)throw new SecurityException("Persistent worker quarantine");
    validateMarker(marker);
   }
   opened=new RandomAccessFile(file,"rw");
   Files.setPosixFilePermissions(file.toPath(),PosixFilePermissions.fromString("rw-------"));
   FileLock acquired=opened.getChannel().tryLock();
   if(acquired==null)throw new SecurityException("Another card process is underway");
   handle=opened;lock=acquired;owner=Thread.currentThread();depth=1;quarantineFile=marker;
  }catch(Exception e){System.err.println("Card lock unavailable: "+e.getClass().getSimpleName());if(opened!=null)try{opened.close();}catch(IOException ignored){}throw new SecurityException("Card transaction lock unavailable or busy");}
 }
 /** Keep exclusion until service restart when a native writer cannot be confirmed stopped. */
 public static synchronized void quarantine(){if(owner!=Thread.currentThread())throw new SecurityException("Transaction owner required");if(quarantined)return;quarantined=true;
  try(FileChannel marker=FileChannel.open(quarantineFile,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
   Files.setPosixFilePermissions(quarantineFile,PosixFilePermissions.fromString("rw-------"));
   marker.write(java.nio.ByteBuffer.wrap("worker_termination_unconfirmed\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));marker.force(true);
   try(FileChannel parent=FileChannel.open(quarantineFile.getParent(),StandardOpenOption.READ)){parent.force(true);}
  }catch(IOException failure){throw new SecurityException("Worker quarantined; persistent marker failed",failure);}
 }
 public static synchronized void release(){
  if(owner!=Thread.currentThread())throw new SecurityException("Transaction lock is not owned by this operation");
  if(quarantined)return;
  if(--depth>0)return;
  try{if(lock!=null)lock.release();}catch(IOException ignored){}
  finally{try{if(handle!=null)handle.close();}catch(IOException ignored){}lock=null;handle=null;owner=null;coordinated=false;depth=0;}
 }
 /** Reserve the cross-process lock for journal + nested legacy engine calls. */
 public static synchronized void beginCoordinated(){beginCoordinated(new File(ROOT,"card-transaction.lock"));}
 static synchronized void beginCoordinated(File file){
  if(owner!=null)throw new SecurityException("Coordinator already active");
  acquire(file);coordinated=true;
 }
 public static synchronized void endCoordinated(){
  if(!coordinated||owner!=Thread.currentThread())throw new SecurityException("Coordinator owner required");
  // A retained inner lock indicates a worker whose termination was not confirmed.
  if(depth!=1)quarantine();
  release();
 }
 public static synchronized boolean isQuarantined(){return quarantined;}
 static boolean persistentQuarantine(){return Files.exists(new File(ROOT,"card-transaction.lock.quarantine").toPath(),LinkOption.NOFOLLOW_LINKS);}
 static void validateMarker(Path marker)throws IOException{
  if(Files.isSymbolicLink(marker)||!Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS)||Files.size(marker)>128
    ||!"worker_termination_unconfirmed\n".equals(new String(Files.readAllBytes(marker),java.nio.charset.StandardCharsets.US_ASCII)))
   throw new IOException("Invalid quarantine evidence");
 }
 public static synchronized boolean isActive(){return owner!=null;}
 static synchronized boolean ownedByCurrentThread(){return owner==Thread.currentThread()&&lock!=null&&lock.isValid();}
}
