#!/usr/bin/env python3
"""Execute the production reconciliation method with fake Binder scopes and card I/O.
No device success claim: this specifically checks composition/authentication ownership.
"""
from pathlib import Path
import os, subprocess, tempfile
root=Path(__file__).resolve().parents[1]
s=(root/'app/src/main/java/dev/sid/fiveberinspector/ProfileUserService.java').read_text()
method=s.split('@Override public synchronized String reconcileOperation() {',1)[1].split('    @Override public synchronized String getCardInfo()',1)[0]
source='''import java.util.*;
public class ReconcileIdentityTest {
 static boolean authorized=true,scope=false,failRead=false;static int reads,journals,checks;
 static class ServiceCaller {
  static void requireApp(){if(!authorized)throw new SecurityException();}
  static class ShellCall implements AutoCloseable {public void close(){scope=false;}}
  static ShellCall asShell(){requireApp();if(scope)throw new SecurityException("nested");scope=true;return new ShellCall();}
 }
 static class JSONObject {
  final Map<String,Object> data=new HashMap<>(); static final Map<String,Map<String,Object>> wire=new HashMap<>();
  JSONObject(){} JSONObject(String encoded){data.putAll(wire.get(encoded));}
  JSONObject put(String k,Object v){data.put(k,v);return this;}boolean has(String k){return data.containsKey(k);}
  public String toString(){String id="r"+wire.size();wire.put(id,new HashMap<>(data));return id;}
 }
 static class OperationJournal {String statusAfterReadOnlyReconciliation(){return "unknown";} boolean requiresReconciliation(){return true;}}
 static class ReadOnlyRecovery{static void acquire(){if(!scope)throw new SecurityException("Shell scope required");}}
 static class CardTransactionLock{static void release(){}}
 OperationJournal journal(){ServiceCaller.requireApp();if(!scope)throw new SecurityException("HAL identity requires Shell scope");journals++;return new OperationJournal();}
 String safeOperationError(String stage,Throwable e){return new JSONObject().put("error",true).toString();}
 String listProfiles()throws Exception{try(AutoCloseable ignored=ServiceCaller.asShell()){reads++;return failRead?new JSONObject().put("error",true).toString():new JSONObject().put("profiles",List.of("unchanged")).toString();}}
 public synchronized String reconcileOperation() {
'''+method+'''
 static void check(boolean ok){checks++;if(!ok)throw new AssertionError("check "+checks);}
 public static void main(String[] args){
  ReconcileIdentityTest service=new ReconcileIdentityTest();JSONObject r=new JSONObject(service.reconcileOperation());
  check(Boolean.TRUE.equals(r.data.get("ok"))&&reads==1&&journals==1&&!scope);
  check("unknown".equals(r.data.get("status"))&&Boolean.TRUE.equals(r.data.get("reviewRequired")));
  authorized=false;r=new JSONObject(service.reconcileOperation());check(r.has("error")&&reads==1&&journals==1&&!scope);
  authorized=true;failRead=true;r=new JSONObject(service.reconcileOperation());check(r.has("error")&&reads==2&&journals==1&&!scope);
  System.out.println("PASS "+checks+" production reconcile composition assertions (simulated identity/card I/O)");
 }
}
'''
with tempfile.TemporaryDirectory() as temp:
 p=Path(temp)/'ReconcileIdentityTest.java';p.write_text(source)
 home=os.environ.get('JAVA_HOME');tool=lambda n:str(Path(home)/'bin'/n) if home else n
 subprocess.run([tool('javac'),str(p)],check=True)
 subprocess.run([tool('java'),'-cp',temp,'ReconcileIdentityTest'],check=True)
