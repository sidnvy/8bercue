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
fixture=r'''import java.util.*;
public class ProfileObservationTest {
 static int checks,reads,discoveries,observations,uid=2000;static boolean authorized=true,isolated=true,worker=false,unstable=false,changed=false,reconciliation=true;
 static String scope="other_card",operation="enable",outcome="unknown";static final String CARD="b".repeat(64);
 String displayedFingerprint,displayedCard,displayedLifecycleFingerprint;String uiSession="session",transport="test";
 static class RemoteException extends Exception{}
 static class Process{static final int SHELL_UID=2000;static int myUid(){return uid;}}
 static class Log{static void i(String t,String v){}static void w(String t,String v){}}
 static class JSONObject{
  final Map<String,Object> data=new HashMap<>();static final Map<String,Map<String,Object>> wire=new HashMap<>();
  JSONObject(){}JSONObject(String encoded){if(encoded.contains("WrongUid")){data.put("ok",false);data.put("error","WrongUid");}else data.putAll(wire.get(encoded));}
  JSONObject put(String k,Object v){data.put(k,v);return this;}boolean has(String k){return data.containsKey(k);}
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
 static class CardTransactionLock{static boolean held;static boolean persistentQuarantine(){return isolated;}static void release(){check(held,"Owned lock released");held=false;}}
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
 static class EnrolledWritePolicy{static boolean isAllowed(String card){return true;}}
 static class OperationJournal{
  boolean requiresReconciliation(){return reconciliation;}
  Properties read(){Properties p=new Properties();p.setProperty("operation",operation);p.setProperty("status",outcome);return p;}
 }
 OperationJournal journal(){return new OperationJournal();}
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
 static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
 static void reset(){reads=discoveries=observations=0;uid=2000;authorized=true;isolated=true;worker=unstable=changed=false;reconciliation=true;scope="other_card";operation="enable";outcome="unknown";CardTransactionLock.held=false;}
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
  reset();worker=true;JSONObject failed=read();check(!failed.optBoolean("ok",true)&&"RECOVERY_WORKER_ACTIVE".equals(failed.optString("recoveryError"))&&reads==0,"Unconfirmed worker still prevents reading");
  reset();unstable=true;failed=read();check(!failed.has("profiles")&&observations==0&&"RECOVERY_INVENTORY_UNSTABLE".equals(failed.optString("recoveryError")),"Unstable inventory is not presented as verified");
  reset();changed=true;failed=read();check(!failed.has("profiles")&&observations==0,"Card swap during read still fails");
  reset();authorized=false;failed=read();check(!failed.has("profiles")&&reads==0,"Unauthorized caller gets no card data");
  reset();uid=123;failed=read();check("WrongUid".equals(failed.optString("error"))&&reads==0,"Wrong UID gets no card access");
  System.out.println("PASS "+checks+" actual listProfiles observation-envelope checks (controlled Binder/card I/O)");
 }
}'''.replace('METHOD',method)
with tempfile.TemporaryDirectory() as tmp:
 p=Path(tmp)/'ProfileObservationTest.java';p.write_text(fixture)
 java=Path(os.environ.get('JAVA_HOME','/usr'))/'bin'
 subprocess.run([str(java/'javac'),str(p)],check=True)
 subprocess.run([str(java/'java'),'-cp',tmp,'ProfileObservationTest'],check=True)
