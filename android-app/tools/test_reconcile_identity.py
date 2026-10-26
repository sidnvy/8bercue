#!/usr/bin/env python3
"""Execute the production reconciliation method with fake Binder scopes and card I/O.
No device success claim: this specifically checks composition/authentication ownership.
"""
from pathlib import Path
import os, subprocess, tempfile
root=Path(__file__).resolve().parents[1]
s=(root/'app/src/main/java/dev/sid/fiveberinspector/ProfileUserService.java').read_text()
method=s.split('@Override public synchronized String reconcileOperation() {',1)[1].split('    @Override public synchronized String getProfileIccid',1)[0]
method=method.replace('} catch (java.io.IOException failure) {','} catch (Exception failure) {')
source='''import java.util.*;
public class ReconcileIdentityTest {
 static boolean authorized=true,scope=false,failRead=false;static int reads,journals,checks,shellScopes;
 static class ServiceCaller {
  static void requireApp(){if(!authorized)throw new SecurityException();}
  static class ShellCall implements AutoCloseable {public void close(){scope=false;shellScopes--;}}
  static ShellCall asShell(){requireApp();if(scope)throw new SecurityException("nested");scope=true;shellScopes++;return new ShellCall();}
 }
 static class JSONObject {
  final Map<String,Object> data=new HashMap<>(); static final Map<String,Map<String,Object>> wire=new HashMap<>();
  JSONObject(){} JSONObject(String encoded){data.putAll(wire.get(encoded));}
  JSONObject put(String k,Object v){data.put(k,v);return this;}boolean has(String k){return data.containsKey(k);}
  boolean optBoolean(String k){return optBoolean(k,false);}
  boolean optBoolean(String k,boolean d){Object v=data.get(k);return v instanceof Boolean?(Boolean)v:d;}
  String optString(String k){return optString(k,"");}
  String optString(String k,String d){Object v=data.get(k);return v==null?d:String.valueOf(v);}
  int optInt(String k,int d){Object v=data.get(k);return v instanceof Number?((Number)v).intValue():d;}
  public String toString(){String id="r"+wire.size();wire.put(id,new HashMap<>(data));return id;}
 }
 static class Log {static void i(String tag,String msg){} static void w(String tag,String msg){}}
 static class CardTransactionLock {static void acquire(){} static void release(){}}
 static class FiveberReaderSelector {
  static class Target {String identityHash="card";String reader="SIM1";}
  static Target discover(){return new Target();}
  static Target verifySelectedCard(Target expected){return expected;}
 }
 static class CardProfileInventory {
  static class Entry {}
  static List<Entry> listKnownCardReadOnly(FiveberReaderSelector.Target expected){return List.of(new Entry());}
 }
 static class OperationJournal {
  String statusAfterReadOnlyReconciliation(){return "unknown";}
  boolean requiresReconciliation(){return true;}
  Properties read(){Properties p=new Properties();p.setProperty("operation","download");return p;}
  static boolean unstartedDownload(Properties record){return false;}
  void closeUnstartedDownload(String card,String snapshot) throws java.io.IOException {}
 }
 OperationJournal journal(){ServiceCaller.requireApp();journals++;return new OperationJournal();}
 String inventoryFingerprint(List<CardProfileInventory.Entry> entries){return "unchanged";}
 String safeOperationError(String stage,Throwable e){return new JSONObject().put("error",true).toString();}
 String listProfiles()throws Exception{try(AutoCloseable ignored=ServiceCaller.asShell()){reads++;return failRead?new JSONObject().put("error",true).toString():new JSONObject().put("profiles",List.of("masked")).put("count",1).toString();}}
 public synchronized String reconcileOperation() {
'''+method+'''
 static void check(boolean ok){checks++;if(!ok)throw new AssertionError("check "+checks);}
 public static void main(String[] args){
  ReconcileIdentityTest service=new ReconcileIdentityTest();JSONObject r=new JSONObject(service.reconcileOperation());
  check(Boolean.TRUE.equals(r.data.get("ok"))&&reads==1&&journals==1&&shellScopes==0);
  check("unknown".equals(r.data.get("status"))&&Boolean.TRUE.equals(r.data.get("reviewRequired")));
  authorized=false;r=new JSONObject(service.reconcileOperation());check(r.has("error")&&reads==1&&journals==1&&shellScopes==0);
  authorized=true;failRead=true;r=new JSONObject(service.reconcileOperation());check(r.has("error")&&reads==2&&journals==1&&shellScopes==0);
  System.out.println("PASS "+checks+" production reconcile composition assertions (simulated identity/card I/O)");
 }
}
'''
with tempfile.TemporaryDirectory() as temp:
 p=Path(temp)/'ReconcileIdentityTest.java';p.write_text(source)
 home=os.environ.get('JAVA_HOME');tool=lambda n:str(Path(home)/'bin'/n) if home else n
 subprocess.run([tool('javac'),str(p)],check=True)
 subprocess.run([tool('java'),'-cp',temp,'ReconcileIdentityTest'],check=True)
