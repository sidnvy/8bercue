#!/usr/bin/env python3
"""Run the exact production listProfiles body with controlled card/Binder I/O.

Checks the user-visible envelope and write flags, including cross-card recovery.
The actual durable observation/lock code has its separate filesystem regression.
"""
from pathlib import Path
import os,subprocess,tempfile
root=Path(__file__).resolve().parents[1]
source=(root/'app/src/main/java/dev/sid/fiveberinspector/ProfileUserService.java').read_text()
method=source.split('    public synchronized String listProfiles() throws RemoteException {',1)[1].split('    private static JSONObject compatibilityJson',1)[0]
method=method.replace('org.json.JSONArray','JSONArray')
status=source.split('@Override public synchronized String getOperationStatus() {',1)[1].split('    @Override public synchronized String reconcileOperation()',1)[0]
fixture=r'''import java.util.*;
public class ProfileObservationTest {
 static dev.sid.fiveberinspector.OperationJournal realJournal;
 static int checks,reads,discoveries,observations,uid=2000;static boolean authorized=true,enrolled=true,isolated=true,worker=false,unstable=false,changed=false,reconciliation=true;
 static String scope="other_card",operation="enable",outcome="unknown";static String CARD="b".repeat(64);
 String displayedFingerprint,displayedCard,displayedLifecycleFingerprint;String uiSession="session",transport="test";
 static class RemoteException extends Exception{}
 static class Process{static final int SHELL_UID=2000;static int myUid(){return uid;}}
 static class Log{static void i(String t,String v){}static void w(String t,String v){}}
 static class JSONObject{
  final Map<String,Object> data=new HashMap<>();static final Map<String,Map<String,Object>> wire=new HashMap<>();
  JSONObject(){}JSONObject(String encoded){if(encoded.contains("WrongUid")){data.put("ok",false);data.put("error","WrongUid");}else data.putAll(wire.get(encoded));}
  Object get(String k){return data.get(k);}JSONObject put(String k,Object v){data.put(k,v);return this;}boolean has(String k){return data.containsKey(k);}
  boolean optBoolean(String k,boolean fallback){return data.get(k) instanceof Boolean?(Boolean)data.get(k):fallback;}
  int optInt(String k,int fallback){return data.get(k) instanceof Integer?(Integer)data.get(k):fallback;}
  String optString(String k){return String.valueOf(data.getOrDefault(k,""));}
  public String toString(){String id="r"+wire.size();wire.put(id,new HashMap<>(data));return id;}
 }
 static class JSONArray {final List<JSONObject> values=new ArrayList<>();JSONArray put(JSONObject v){values.add(v);return this;}int length(){return values.size();}}
 static class ServiceCaller{
  static void requireApp(){if(!authorized)throw new SecurityException();}
  static ShellCall asShell(){requireApp();return new ShellCall();}
  static class ShellCall implements AutoCloseable{public void close(){}}
 }
 static class CardTransactionLock{static boolean held;static boolean persistentQuarantine(){return isolated;}static boolean isQuarantined(){return false;}static void release(){check(held,"Owned lock released");held=false;}}
 static class ReadOnlyRecovery{
  static class Failure extends SecurityException{String code;Failure(String c){code=c;}}
  static void acquire(){if(worker)throw new Failure("RECOVERY_WORKER_ACTIVE");check(!CardTransactionLock.held,"No nested lock");CardTransactionLock.held=true;}
  static String observed(String card,String fingerprint){check(CardTransactionLock.held,"Observation under exclusive lock");observations++;return scope;}
  static String scope(Properties journal,String card){return scope;}
  static String code(Throwable t){return t instanceof Failure?((Failure)t).code:"";}
 }
 static class FiveberReaderSelector{
  static class Target{String identityHash,reader="telephony.1.0",eidMasked="masked";Target(String v){identityHash=v;}}
  static Target discover(){discoveries++;return new Target(changed&&discoveries>1?"d".repeat(64):CARD);}
  static void clearCompatibilityReport(){}static CompatibilityReport compatibilityReport(){return new CompatibilityReport();}
 }
 static class CompatibilityReport{String route(){return "test";}}
 static JSONObject compatibilityJson(CompatibilityReport r,boolean verified){return new JSONObject().put("profileReadVerified",verified);}
 static class NativeLpacBundle{static void ensureInstalled(){}}
 static class PhoneNativeLpac{
  static class StageException extends Exception{String phase="test";}
  static JSONObject listProfiles(){return new JSONObject().put("ok",true);}
 }
 static class CardProfileInventory{
  static class Entry{String iccid="1234567890",provider="Test",name="Test",nickname="";boolean enabled=true;}
  static List<Entry> list(){reads++;Entry row=new Entry();if(unstable&&reads>1)row.nickname="changed";return List.of(row);}
 }
 static class EnrolledWritePolicy{static boolean isAllowed(String card){return enrolled;}}
 static class OperationJournal{
  String statusAfterReadOnlyReconciliation()throws Exception{return realJournal==null?outcome:realJournal.statusAfterReadOnlyReconciliation();}
  boolean requiresReconciliation()throws Exception{return realJournal==null?reconciliation:realJournal.requiresReconciliation();}
  Properties read()throws Exception{if(realJournal!=null)return realJournal.read();Properties p=new Properties();p.setProperty("operation",operation);p.setProperty("status",outcome);return p;}
  boolean canReviewObservedLifecycle(String card)throws Exception{return realJournal==null
   ?operation.matches("enable|disable|rename|switch")&&outcome.matches("running|unknown|observed_unknown")
   :realJournal.canReviewObservedLifecycle(card);}
 }
 OperationJournal journal(){return new OperationJournal();}
 String safeOperationError(String stage,Throwable e){return new JSONObject().put("ok",false).toString();}
 static class PhoneNotificationDelivery{static JSONObject statusUnderLock(String card){check(CardTransactionLock.held,"Status must use the already-owned lock, not acquire a nested lock");return new JSONObject().put("notificationStatus","none");}}
 static class ProfileLifecycleGuard{
  enum Operation{RENAME,ENABLE,DISABLE,DELETE}static class Row{}
  static String selectionFingerprint(List<Row> rows){return "test";}
 }
 static class PortableProfileLifecycle{static List<ProfileLifecycleGuard.Row> rows(List<CardProfileInventory.Entry> rows){return List.of(new ProfileLifecycleGuard.Row());}}
 static class ProfileUiIdentity{static String key(String session,String card,String id){return "opaque-choice";}}
 static class ReaderDescriptor{
  static String TELEPHONY_PREFIX="telephony.",HIDL_PREFIX="hidl.";
  static boolean lifecycleAllowed(String r,ProfileLifecycleGuard.Operation o,boolean enabled){return true;}
 }
 static class SystemHalAdapter{static String describeReader(String reader){return "test card";}}
 static class SecureElementFailure{
  enum Reason{NO_CARD}Reason reason=Reason.NO_CARD;static SecureElementFailure find(Throwable t){return null;}
 }
 static String safeLabel(String v){return v;}
 static boolean allowed(String card,List<ProfileLifecycleGuard.Row> rows,CardProfileInventory.Entry e,ProfileLifecycleGuard.Operation op){return true;}
 static String inventoryFingerprint(List<CardProfileInventory.Entry> rows){return rows.get(0).nickname.isEmpty()?"c".repeat(64):"d".repeat(64);}
 public synchronized String listProfiles()throws RemoteException{METHOD
 public synchronized String getOperationStatus(){STATUS_METHOD
 static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
 static void reset(){CARD="b".repeat(64);realJournal=null;reads=discoveries=observations=0;uid=2000;authorized=true;enrolled=true;isolated=true;worker=unstable=changed=false;reconciliation=true;scope="other_card";operation="enable";outcome="unknown";CardTransactionLock.held=false;}
 static JSONObject read()throws Exception{return new JSONObject(new ProfileObservationTest().listProfiles());}
 public static void main(String[] args)throws Exception{
  for(String relation:List.of("same_card","other_card","unbound")){
   reset();scope=relation;JSONObject r=read();
   check(r.optBoolean("ok",false)&&r.has("profiles"),"Verified current card remains readable: "+relation);
   check(((JSONArray)r.data.get("profiles")).length()==1,"Profile list returned");
   check(reads==2&&discoveries==2&&observations==1,"Double read and final card/reader check retained");
   check(!r.optBoolean("writeQualified",true)&&r.optBoolean("changesBlocked",false),"Observation does not unlock writes");
   check(r.optBoolean("recoveryReviewEligible",false)==relation.equals("same_card"),"Offer review only for the currently observed original card");
   check(relation.equals(r.optString("recoveryScope")),"Recovery scope not confused with another card");
   check((relation.equals("same_card")?"READ_ONLY_RECOVERED":"READ_ONLY_CURRENT_CARD").equals(r.optString("recoveryState")),"Current-card read does not assert recovery of old card");
   JSONObject profile=((JSONArray)r.data.get("profiles")).values.get(0);
   for(String action:List.of("canRename","canEnable","canDisable","canDelete"))check(!profile.optBoolean(action,true),"Mutation remains unavailable");
   check(!CardTransactionLock.held,"Lock released after read");
  }
  for(String relation:List.of("same_card","other_card","unbound")){
   reset();isolated=false;scope=relation;JSONObject r=read();
   check(r.has("profiles")&&!r.optBoolean("writeQualified",true)&&r.optBoolean("changesBlocked",false),"Unknown journal without quarantine still permits only observation");
   check(r.optBoolean("recoveryReviewEligible",false)==relation.equals("same_card"),"Same-card review is contextual even without quarantine");
  }
  for(String kind:List.of("download","delete")){
   reset();scope="same_card";operation=kind;JSONObject r=read();check(!r.optBoolean("recoveryReviewEligible",true),"Lifecycle review cannot resolve "+kind);
  }
  reset();scope="same_card";outcome="verified";JSONObject currentVerified=read();check(!currentVerified.optBoolean("recoveryReviewEligible",true),"Uncertainty elsewhere in history cannot review a verified current record");
  reset();isolated=false;reconciliation=false;JSONObject healthy=read();check(healthy.has("profiles")&&healthy.optBoolean("writeQualified",false),"Healthy path unchanged");check(reads==1&&observations==0,"Healthy card needs no recovery receipt");
  reset();scope="same_card";enrolled=false;JSONObject unsupported=read();check(unsupported.has("profiles")&&!unsupported.optBoolean("writeQualified",true)&&!unsupported.optBoolean("recoveryReviewEligible",true),"Read-only card without management admission cannot offer an impossible review");
  reset();worker=true;JSONObject failed=read();check(!failed.optBoolean("ok",true)&&"RECOVERY_WORKER_ACTIVE".equals(failed.optString("recoveryError"))&&reads==0,"Unconfirmed worker still prevents reading");
  reset();unstable=true;failed=read();check(!failed.has("profiles")&&observations==0&&"RECOVERY_INVENTORY_UNSTABLE".equals(failed.optString("recoveryError")),"Unstable inventory is not presented as verified");
  reset();changed=true;failed=read();check(!failed.has("profiles")&&observations==0,"Card swap during read still fails");
  reset();authorized=false;failed=read();check(!failed.has("profiles")&&reads==0,"Unauthorized caller gets no card data");
  reset();uid=123;failed=read();check("WrongUid".equals(failed.optString("error"))&&reads==0,"Wrong UID gets no card access");
  // Integrate the actual on-disk journal with the actual listProfiles body. The old
  // envelope offered confirmation even when its durable commit could never succeed.
  for(String scenario:List.of("single","other-unknown","history-mismatch","missing-history","pending","reviewed-quarantine")){
   reset();scope="same_card";
   java.nio.file.Path directory=java.nio.file.Files.createTempDirectory(java.nio.file.Path.of(System.getProperty("fixture.root")),"journal-");
   realJournal=new dev.sid.fiveberinspector.OperationJournal(directory.toFile());
   realJournal.begin(new dev.sid.fiveberinspector.OperationJournal.Binding("enable",CARD,CARD,CARD,CARD));realJournal.finish("unknown");
   Properties current=realJournal.read();String tx=current.getProperty("txId");
   java.nio.file.Path original=directory.resolve("history").resolve(tx+".properties");
   if(scenario.equals("other-unknown")){
    Properties other=realJournal.read();String otherTx=UUID.randomUUID().toString();other.setProperty("txId",otherTx);
    try(var out=java.nio.file.Files.newOutputStream(directory.resolve("history").resolve(otherTx+".properties"))){other.store(out,"Synthetic history only");}
   }else if(scenario.equals("history-mismatch")){
    Properties different=realJournal.read();different.setProperty("startedAt","1");
    try(var out=java.nio.file.Files.newOutputStream(original)){different.store(out,"Synthetic mismatch only");}
   }else if(scenario.equals("missing-history"))java.nio.file.Files.delete(original);
   else if(scenario.equals("pending"))java.nio.file.Files.writeString(directory.resolve("history/interrupted.pending"),"incomplete");
   else if(scenario.equals("reviewed-quarantine"))realJournal.reviewObservedLifecycle(tx,CARD,CARD,
    dev.sid.fiveberinspector.OperationJournal.recordDigest(current),"c".repeat(64));
   byte[] before=java.nio.file.Files.readAllBytes(directory.resolve("operation.properties"));
   JSONObject result=read();
   check(result.has("profiles")&&!result.optBoolean("writeQualified",true),"History does not prevent safe observation or release write quarantine: "+scenario);
   check(result.optBoolean("recoveryReviewEligible",false)==List.of("single","reviewed-quarantine").contains(scenario),"Only an available durable review is offered: "+scenario);
   check(Arrays.equals(before,java.nio.file.Files.readAllBytes(directory.resolve("operation.properties"))),"Availability never rewrites the original outcome: "+scenario);
   check(realJournal.requiresReconciliation()==!scenario.equals("reviewed-quarantine"),"Availability never resolves uncertainty: "+scenario);
  }
  reset();isolated=false;
  java.nio.file.Path legacyRoot=java.nio.file.Files.createTempDirectory(java.nio.file.Path.of(System.getProperty("fixture.root")),"scoped-integration-");
  dev.sid.fiveberinspector.OperationJournal old=new dev.sid.fiveberinspector.OperationJournal(legacyRoot.toFile());
  String oldCard="a".repeat(64);old.begin(new dev.sid.fiveberinspector.OperationJournal.Binding("enable",oldCard,oldCard,oldCard,oldCard));old.finish("unknown");
  for(String selected:List.of("b".repeat(64),oldCard,"b".repeat(64),oldCard)){
   CARD=selected;scope=selected.equals(oldCard)?"same_card":"none";realJournal=dev.sid.fiveberinspector.OperationJournal.forCard(legacyRoot.toFile(),CARD);
   JSONObject state=new JSONObject(new ProfileObservationTest().getOperationStatus());JSONObject result=read();boolean blocked=selected.equals(oldCard);
   check(state.optBoolean("ok",false)&&state.optBoolean("requiresReconciliation",false)==blocked,"Actual service status only pauses the selected card");
   check(result.optBoolean("writeQualified",false)==!blocked,"Actual inventory exposes management independently for another card");
   check(result.optBoolean("recoveryReviewEligible",false)==blocked,"Recovery action follows the original card across repeated choices");
  }
  System.out.println("PASS "+checks+" actual listProfiles observation-envelope checks (controlled Binder/card I/O)");
 }
}'''.replace('STATUS_METHOD',status).replace('METHOD',method)
with tempfile.TemporaryDirectory() as tmp:
 p=Path(tmp)/'ProfileObservationTest.java';p.write_text(fixture)
 java=Path(os.environ.get('JAVA_HOME','/usr'))/'bin'
 subprocess.run([str(java/'javac'),'-d',tmp,str(root/'app/src/main/java/dev/sid/fiveberinspector/OperationJournal.java'),str(p)],check=True)
 subprocess.run([str(java/'java'),'-Dfixture.root='+tmp,'-cp',tmp,'ProfileObservationTest'],check=True)
