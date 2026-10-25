#!/usr/bin/env python3
"""Execute actual recovery observation/lock/journal code with temporary filesystem roots.

No phone I/O is simulated as hardware success. Android process signaling is forbidden
in this harness; worker/quiescence predicates have separate production-code tests.
"""
from pathlib import Path
import os,subprocess,tempfile
root=Path(__file__).resolve().parents[1]
src=root/'app/src/main/java/dev/sid/fiveberinspector'
with tempfile.TemporaryDirectory() as tmp:
 t=Path(tmp);pkg=t/'dev/sid/fiveberinspector';pkg.mkdir(parents=True)
 for name in ['ReadOnlyRecovery','CardTransactionLock','OperationJournal','QuarantineReview','RecoveryProcessGuard','ConnectionCommand']:
  s=(src/(name+'.java')).read_text()
  if name=='ReadOnlyRecovery':
   s=s.replace('Paths.get("/data/local/tmp/fiveber-lpac-lab")','Paths.get(System.getProperty("fixture.root"))')
   s=s.replace('new File("/data/local/tmp/fiveber-rescue-state")','new File(System.getProperty("fixture.journal"))')
  if name=='CardTransactionLock':s=s.replace('new File("/data/local/tmp/fiveber-lpac-lab")','new File(System.getProperty("fixture.root"))')
  (pkg/(name+'.java')).write_text(s)
 (pkg/'NativeLpacBundle.java').write_text('''package dev.sid.fiveberinspector;
import java.io.*;import java.nio.file.*;import java.security.*;import java.util.*;
final class NativeLpacBundle {
 static String installedApk(){return System.getProperty("fixture.apk");}
 static String digest(File f)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f.toPath())));}
}''')
 android=t/'android/os';android.mkdir(parents=True)
 (android/'Process.java').write_text('''package android.os;
public final class Process {public static int myUid(){return 2000;}public static int myPid(){return 99;}
public static void sendSignal(int pid,int signal){throw new AssertionError("Observation must not signal a process");}}
''')
 (pkg/'ObservationTest.java').write_text(r'''package dev.sid.fiveberinspector;
import java.io.*;import java.nio.file.*;import java.util.*;
public class ObservationTest {
 static int checks;
 static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 static Map<String,String> snapshot(Path dir)throws Exception{
  Map<String,String> result=new TreeMap<>();try(var paths=Files.walk(dir)){
   for(Path p:paths.filter(Files::isRegularFile).toList())result.put(dir.relativize(p).toString(),NativeLpacBundle.digest(p.toFile()));
  }return result;
 }
 static void denyWrite(File lock){
  boolean denied=false;try{CardTransactionLock.acquire(lock);}catch(SecurityException expected){denied=true;}
  if(!denied){CardTransactionLock.release();throw new AssertionError("Observation granted mutation lock");}checks++;
 }
 static Set<String> phases(Path root)throws Exception{
  Set<String> values=new HashSet<>();try(var paths=Files.list(root.resolve("read-only-recovery"))){
   for(Path p:paths.toList()){Properties r=new Properties();try(var in=Files.newInputStream(p)){r.load(in);}values.add(r.getProperty("phase"));check("false".equals(r.getProperty("writesAuthorized")),"Receipt cannot authorize writes");}
  }return values;
 }
 public static void main(String[] args)throws Exception{
  Path base=Paths.get(System.getProperty("fixture.base"));Path root=base.resolve("runtime"),j=base.resolve("journal");Files.createDirectories(root);
  Path apk=base.resolve("test.apk");Files.writeString(apk,"synthetic build only");
  System.setProperty("fixture.root",root.toString());System.setProperty("fixture.journal",j.toString());System.setProperty("fixture.apk",apk.toString());
  String a="a".repeat(64),b="b".repeat(64),fingerprint="c".repeat(64);
  OperationJournal journal=new OperationJournal(j.toFile());journal.begin(new OperationJournal.Binding("enable",a,a,a,a));journal.finish("unknown");
  Path marker=root.resolve("card-transaction.lock.quarantine");byte[] markerBytes="worker_termination_unconfirmed\n".getBytes();Files.write(marker,markerBytes);
  File lock=root.resolve("card-transaction.lock").toFile();Map<String,String> before=snapshot(j);
  CardTransactionLock.acquire(lock,true);try{ReadOnlyRecovery.observed(a,fingerprint);}finally{CardTransactionLock.release();}
  check(phases(root).contains("stable_inventory_read_only"),"Original-card observation retained");
  boolean denied=false;try{ReadOnlyRecovery.observed(b,fingerprint);}catch(ReadOnlyRecovery.Failure expected){denied=true;}
  check(denied,"Observation evidence requires the currently owned lock");
  CardTransactionLock.acquire(lock,true);try{
   denied=false;try{ReadOnlyRecovery.observed("masked",fingerprint);}catch(ReadOnlyRecovery.Failure expected){denied=true;}
   check(denied,"Masked card identity cannot create observation evidence");
   denied=false;try{ReadOnlyRecovery.observed(b,"partial");}catch(ReadOnlyRecovery.Failure expected){denied=true;}
   check(denied,"Incomplete inventory digest cannot create observation evidence");
  }finally{CardTransactionLock.release();}
  CardTransactionLock.acquire(lock,true);try{
   try{ReadOnlyRecovery.observed(b,fingerprint);}catch(ReadOnlyRecovery.Failure failure){throw new AssertionError("A verified current-card read must not be discarded because an old operation belongs to another card",failure);}
  }finally{CardTransactionLock.release();}
  check(phases(root).contains("other_card_inventory_read_only"),"Other-card observation distinguished from recovery of the original card");
  check(before.equals(snapshot(j)),"Original journal and history bytes unchanged");
  check(Arrays.equals(markerBytes,Files.readAllBytes(marker)),"Quarantine bytes unchanged");
  check(journal.requiresReconciliation(),"Original operation remains unresolved");denyWrite(lock);
  // Historical valid records can lack a card binding. Do not falsely call that a card swap.
  Properties legacy=new Properties();legacy.setProperty("status","unknown");legacy.setProperty("operation","enable");legacy.setProperty("startedAt","1");
  try(var out=Files.newOutputStream(j.resolve("operation.properties"))){legacy.store(out,"synthetic legacy fixture");}
  before=snapshot(j);
  CardTransactionLock.acquire(lock,true);try{ReadOnlyRecovery.observed(b,fingerprint);}finally{CardTransactionLock.release();}
  check(phases(root).contains("unbound_inventory_read_only"),"Missing historical binding permits observation without inventing same-card evidence");
  check(before.equals(snapshot(j)),"Legacy records preserved exactly");check(journal.requiresReconciliation(),"Legacy uncertainty remains blocked");denyWrite(lock);
  check(Arrays.equals(markerBytes,Files.readAllBytes(marker)),"Reads never archive quarantine");
  System.out.println("PASS "+checks+" actual recovery observation/journal/exclusion checks (temporary filesystem, no device claim)");
 }
}''')
 java=Path(os.environ.get('JAVA_HOME','/usr'))/'bin'
 subprocess.run([str(java/'javac'),'-d',str(t)]+[str(p) for p in t.rglob('*.java')],check=True)
 subprocess.run([str(java/'java'),'-Dfixture.base='+str(t/'fixture'),'-cp',str(t),'dev.sid.fiveberinspector.ObservationTest'],check=True)
