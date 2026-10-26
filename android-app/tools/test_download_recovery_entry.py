#!/usr/bin/env python3
"""Execute the production review-observation method with real journals/fingerprints.

Only card/process I/O is controlled; this does not claim a hardware recovery.
"""
from pathlib import Path
import os, subprocess, tempfile

root = Path(__file__).resolve().parents[1]
src = root / 'app/src/main/java/dev/sid/fiveberinspector'
service = (src / 'ProfileUserService.java').read_text()
observation = service.split('    private RecoveryReviewPlan observeLifecycleForReview(OperationJournal journal) throws Exception {', 1)[1].split('    /**\n     * Prepare ONLY', 1)[0]
fingerprint = service.split('    private static String inventoryFingerprint(java.util.List<CardProfileInventory.Entry> entries) throws Exception {', 1)[1].split('    private static boolean allowed(', 1)[0]
fixture = r'''package dev.sid.fiveberinspector;
import java.nio.file.*;import java.util.*;
public final class DownloadReviewEntryTest {
 static int checks,reads,discoveries,nativeReads;static boolean quarantine,enrolled=true,unstable,changedCard;
 static String CARD="b".repeat(64),OTHER="a".repeat(64);static List<CardProfileInventory.Entry> entries;
 static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 static class CardTransactionLock {static boolean persistentQuarantine(){return quarantine;}}
 static class ReadOnlyRecovery {static class Failure extends Exception{Failure(String code){super(code);}}}
 static class CardProfileInventory {
  static class Entry {String iccid="1234567890123456789",provider="Test provider",name="Test name",nickname="";boolean enabled;}
  static List<Entry> list(){reads++;if(unstable&&reads>1){Entry e=new Entry();e.nickname="changed";return List.of(e);}return entries;}
 }
 static class FiveberReaderSelector {
  static class Target {String identityHash,reader="test.reader";Target(String card){identityHash=card;}}
  static Target discover(){return new Target(changedCard&&++discoveries>1?OTHER:CARD);}
 }
 static class EnrolledWritePolicy {static boolean isAllowed(String card){return enrolled;}}
 static class NativeLpacBundle {static void ensureInstalled(){}}
 static class PhoneNativeLpac {static void listProfiles(){nativeReads++;}}
 static class PortableProfileLifecycle {
  static List<ProfileLifecycleGuard.Row> rows(List<CardProfileInventory.Entry> entries){
   List<ProfileLifecycleGuard.Row> result=new ArrayList<>();
   for(var e:entries)result.add(new ProfileLifecycleGuard.Row(e.iccid,e.enabled,e.nickname,e.provider,e.name));return result;
  }
 }
 static class RecoveryReviewPlan {
  String tx,card,reader,snapshot,recordDigest,operation;boolean alreadyReviewed;
  RecoveryReviewPlan(String tx,String card,String reader,String snapshot,String digest,String operation,boolean reviewed){this.tx=tx;this.card=card;this.reader=reader;this.snapshot=snapshot;recordDigest=digest;this.operation=operation;alreadyReviewed=reviewed;}
 }
 private static String inventoryFingerprint(java.util.List<CardProfileInventory.Entry> entries) throws Exception {FINGERPRINT
 private RecoveryReviewPlan observeLifecycleForReview(OperationJournal journal) throws Exception {OBSERVATION
 static OperationJournal fixture(Path root,String status)throws Exception{
  quarantine=false;enrolled=true;unstable=changedCard=false;reads=discoveries=nativeReads=0;
  OperationJournal journal=OperationJournal.forCard(root.toFile(),CARD);
  journal.begin(new OperationJournal.Binding("download",CARD,OTHER,inventoryFingerprint(entries),OTHER));journal.finish(status);return journal;
 }
 static void reject(DownloadReviewEntryTest test,OperationJournal journal,String why)throws Exception{
  boolean denied=false;try{test.observeLifecycleForReview(journal);}catch(Exception expected){denied=true;}check(denied,why);check(journal.requiresReconciliation(),"A denied review never clears the original uncertainty");
 }
 public static void main(String[] args)throws Exception{
  DownloadReviewEntryTest test=new DownloadReviewEntryTest();
  for(boolean empty:List.of(true,false)){
   entries=empty?List.of():List.of(new CardProfileInventory.Entry());Path root=Files.createTempDirectory("download-review-entry-");OperationJournal journal=fixture(root,"unknown");
   Path current=root.resolve("cards").resolve(CARD).resolve("operation.properties");byte[] before=Files.readAllBytes(current);
   RecoveryReviewPlan plan=test.observeLifecycleForReview(journal);
   check(plan.operation.equals("download")&&plan.card.equals(CARD)&&!plan.alreadyReviewed,"Exact download review available for empty and populated cards");
   check(plan.snapshot.equals(inventoryFingerprint(entries)),"Review uses the same full-inventory encoding as the original download");
   check(reads==2&&nativeReads==1,"Independent native inventory plus two same-card Java observations");
   check(Arrays.equals(before,Files.readAllBytes(current))&&journal.requiresReconciliation(),"Read-only preview preserves the original unknown outcome");
  }
  entries=List.of(new CardProfileInventory.Entry());
  for(String scenario:List.of("unstable","changed-inventory","swapped-card","quarantine","unsupported","missing-history","running")){
   Path root=Files.createTempDirectory("download-review-negative-");OperationJournal journal;
   if(scenario.equals("running")){
    fixture(root,"unknown"); // Preserve a complete fixture, then model a crash before finish.
    journal=OperationJournal.forCard(root.toFile(),CARD);Properties record=journal.read();record.setProperty("status","running");
    for(Path p:List.of(root.resolve("cards").resolve(CARD).resolve("operation.properties"),root.resolve("cards").resolve(CARD).resolve("history").resolve(record.getProperty("txId")+".properties")))try(var out=Files.newOutputStream(p)){record.store(out,"Synthetic running fixture");}
   }else journal=fixture(root,"unknown");
   if(scenario.equals("unstable"))unstable=true;
   if(scenario.equals("changed-inventory")){var e=new CardProfileInventory.Entry();e.nickname="changed before review";entries=List.of(e);}
   if(scenario.equals("swapped-card"))changedCard=true;
   if(scenario.equals("quarantine"))quarantine=true;
   if(scenario.equals("unsupported"))enrolled=false;
   if(scenario.equals("missing-history"))Files.delete(root.resolve("cards").resolve(CARD).resolve("history").resolve(journal.read().getProperty("txId")+".properties"));
   reject(test,journal,scenario);entries=List.of(new CardProfileInventory.Entry());
  }
  System.out.println("PASS "+checks+" actual download-review observation entry checks (controlled card I/O, real fingerprints/journals)");
 }
}'''.replace('FINGERPRINT', fingerprint).replace('OBSERVATION', observation)
with tempfile.TemporaryDirectory() as temp:
    path = Path(temp) / 'DownloadReviewEntryTest.java'
    path.write_text(fixture)
    java = Path(os.environ.get('JAVA_HOME', '/usr')) / 'bin'
    sources = [str(src / (name + '.java')) for name in ['OperationJournal', 'DownloadGuard', 'ProfileLifecycleGuard']]
    subprocess.run([str(java / 'javac'), '-d', temp, *sources, str(path)], check=True)
    subprocess.run([str(java / 'java'), '-cp', temp, 'dev.sid.fiveberinspector.DownloadReviewEntryTest'], check=True)
