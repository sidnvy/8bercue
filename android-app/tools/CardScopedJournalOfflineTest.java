package dev.sid.fiveberinspector;
import java.io.*;import java.nio.file.*;import java.util.*;
/** Real journal and filesystem tests; no card I/O or device qualification claim. */
public final class CardScopedJournalOfflineTest {
 static int checks;static final String A="a".repeat(64),B="b".repeat(64),C="c".repeat(64);
 static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 interface Task{void run()throws Exception;}
 static void deny(Task t,String why)throws Exception{boolean denied=false;try{t.run();}catch(IOException|SecurityException expected){denied=true;}check(denied,why);}
 static OperationJournal.Binding intent(String card){return new OperationJournal.Binding("enable",card,C,C,C);}
 static Map<String,String> original(Path root)throws Exception{
  Map<String,String> result=new TreeMap<>();try(var paths=Files.walk(root)){
   for(Path p:paths.filter(Files::isRegularFile).toList())if(!root.relativize(p).startsWith("cards"))result.put(root.relativize(p).toString(),Base64.getEncoder().encodeToString(Files.readAllBytes(p)));
  }return result;
 }
 static void store(Path path,Properties p)throws Exception{try(var out=Files.newOutputStream(path)){p.store(out,"Synthetic journal fixture");}}
 public static void main(String[] args)throws Exception{
  Path root=Files.createTempDirectory("card-scoped-journal-");OperationJournal old=new OperationJournal(root.toFile());
  old.begin(intent(A));old.finish("unknown");Map<String,String> baseline=original(root);
  OperationJournal a=OperationJournal.forCard(root.toFile(),A),b=OperationJournal.forCard(root.toFile(),B);
  check(a.requiresReconciliation(),"Original card retains its uncertainty");
  check(!b.requiresReconciliation()&&"idle".equals(b.read().getProperty("status")),"New card is independent of old unknown operation");
  b.begin(intent(B));b.finish("verified");
  check(a.requiresReconciliation()&&!b.requiresReconciliation(),"New-card transaction cannot settle or inherit old-card uncertainty");
  check(baseline.equals(original(root)),"Legacy current/history bytes are unchanged after new-card transaction");
  check(A.equals(a.read().getProperty("card"))&&B.equals(b.read().getProperty("card")),"Each card restores its own current operation");
  deny(()->b.begin(intent(A)),"A bound operation cannot enter another card journal");
  Properties previous=a.read();check(a.canReviewObservedLifecycle(A),"Original card can still review its old operation");
  a.reviewObservedLifecycle(previous.getProperty("txId"),A,C,OperationJournal.recordDigest(previous),C);
  check(!a.requiresReconciliation()&&!b.requiresReconciliation(),"Review is scoped to its original card");
  check(baseline.equals(original(root)),"Scoped review preserves all legacy bytes");
  a.begin(intent(A));a.finish("unknown");
  check(OperationJournal.forCard(root.toFile(),A).requiresReconciliation(),"Restart retains A's new unknown operation");
  check(!OperationJournal.forCard(root.toFile(),B).requiresReconciliation(),"Restart does not transfer A's pause to B");
  b.begin(intent(B));b.finish("unknown");
  check(a.requiresReconciliation()&&b.requiresReconciliation(),"Two cards retain separate unknown outcomes");
  check(!OperationJournal.forCard(root.toFile(),C).requiresReconciliation(),"Third card remains independently available");
  // Historical records for more than one card are filtered without dropping any.
  Path mixed=Files.createTempDirectory("card-mixed-history-");OperationJournal legacy=new OperationJournal(mixed.toFile());legacy.begin(intent(A));legacy.finish("unknown");
  Properties other=legacy.read();String id=UUID.randomUUID().toString();other.setProperty("txId",id);other.setProperty("card",B);store(mixed.resolve("history").resolve(id+".properties"),other);
  OperationJournal ma=OperationJournal.forCard(mixed.toFile(),A),mb=OperationJournal.forCard(mixed.toFile(),B);
  check(ma.canReviewObservedLifecycle(A)&&mb.canReviewObservedLifecycle(B),"Another card's unresolved history cannot make same-card review impossible");
  Properties current=ma.read();ma.reviewObservedLifecycle(current.getProperty("txId"),A,C,OperationJournal.recordDigest(current),C);
  check(!ma.requiresReconciliation()&&mb.requiresReconciliation(),"Reviewing A never reviews B");
  check(id.equals(mb.readTransaction(id).getProperty("txId")),"Notification recovery can find bound legacy transaction");
  deny(()->ma.readTransaction(id),"Transaction lookup cannot cross card identity");
  // Unknown legacy records without a trustworthy card binding cannot be assigned by guess.
  Path unbound=Files.createTempDirectory("card-unbound-");OperationJournal uj=new OperationJournal(unbound.toFile());uj.begin("enable");uj.finish("unknown");
  deny(()->OperationJournal.forCard(unbound.toFile(),B).requiresReconciliation(),"Unbound legacy uncertainty fails closed");
  Files.writeString(mixed.resolve("operation.pending"),"incomplete");
  deny(()->OperationJournal.forCard(mixed.toFile(),C).requiresReconciliation(),"Interrupted unbound durability commit fails closed");
  Path rollback=Files.createTempDirectory("card-clock-rollback-");OperationJournal clock=new OperationJournal(rollback.toFile());clock.begin(intent(A));clock.finish("unknown");
  Properties pointer=clock.read();pointer.setProperty("startedAt","1");store(rollback.resolve("operation.properties"),pointer);store(rollback.resolve("history").resolve(pointer.getProperty("txId")+".properties"),pointer);
  Properties earlier=clock.read();String earlierId=UUID.randomUUID().toString();earlier.setProperty("txId",earlierId);earlier.setProperty("startedAt","9999999999999");earlier.setProperty("status","verified");store(rollback.resolve("history").resolve(earlierId+".properties"),earlier);
  check(pointer.getProperty("txId").equals(OperationJournal.forCard(rollback.toFile(),A).read().getProperty("txId")),"Legacy current pointer survives wall-clock rollback");
  Path linkedHistory=Files.createTempDirectory("card-linked-history-");OperationJournal linked=OperationJournal.forCard(linkedHistory.toFile(),B);
  Files.createSymbolicLink(linkedHistory.resolve("cards").resolve(B).resolve("history"),mixed.resolve("history"));
  deny(()->linked.readTransaction(id),"History lookup cannot follow a symlink across card journals");
  Path symlink=Files.createTempDirectory("card-symlink-");Files.createSymbolicLink(symlink.resolve("cards"),root.resolve("cards"));
  deny(()->OperationJournal.forCard(symlink.toFile(),B),"Symlink journal root rejected");
  System.out.println("PASS "+checks+" actual card-scoped journal, restart, legacy and isolation assertions");
 }
}
