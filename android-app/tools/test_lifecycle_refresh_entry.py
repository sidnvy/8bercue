#!/usr/bin/env python3
"""Execute actual scoped HAL and lifecycle code with controlled native/card I/O.
The real transaction lock uses a temporary directory; settling deadlines/delays
are shortened only in the fixture. No Android/card compatibility is claimed.
"""
from pathlib import Path
import os, subprocess, tempfile

root = Path(__file__).resolve().parents[1]
src = root / 'app/src/main/java/dev/sid/fiveberinspector'
fixture = r'''
package dev.sid.fiveberinspector;
import java.util.*;
import org.json.JSONObject;
public class LifecycleRefreshEntryTest {
 static int checks,nativeCalls,reads,discoveries;static boolean executed;static String mode;
 static final String CARD="a".repeat(64),ID="1234567890123456789";
 static List<CardProfileInventory.Entry> inventory=List.of(new CardProfileInventory.Entry(false));
 static void check(boolean v,String message){checks++;if(!v)throw new AssertionError(message);}
 static JSONObject req(String fn,String param){return new JSONObject().put("type","apdu").put("payload",new JSONObject().put("func",fn).put("param",param));}
 static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format("%02X",b&255));return s.toString();}
 static void bridge()throws Exception{
  ProfileLifecycleGuard.Operation op=mode.equals("bridge-rename")?ProfileLifecycleGuard.Operation.RENAME:ProfileLifecycleGuard.Operation.ENABLE;
  ScopedLifecycleHalBridge b=new ScopedLifecycleHalBridge(op,ID,"","SIM2",CARD);
  b.handle(req("logic_channel_open","A0000005591010FFFFFFFF8900000100"));
  if(!mode.equals("bridge-before-submit"))b.handle(req("transmit",hex(ProfileLifecycleGuard.command(op,ID,"",2))));
  b.close();b.close();b.handle(req("disconnect",null));
  check(SystemHalAdapter.hal.closed==1,"Never retry the invalidated logical channel");
  boolean defer=mode.equals("bridge-refresh");
  check(b.refreshCleanupFailed()==defer,"Only a submitted REFRESH operation defers cleanup");
  check(CardTransactionLock.isQuarantined()!=defer,"Other close failures remain fail closed");
  check(SystemHalAdapter.hal.frames==(mode.equals("bridge-before-submit")?0:1),"No write replay");
 }
 public static void main(String[] args)throws Exception{
  mode=args[0];CardTransactionLock.beginCoordinated();
  if(mode.startsWith("bridge-"))bridge();
  else {
   JSONObject plan=PortableProfileLifecycle.prepare("enable",0,"");
   reads=discoveries=0;
   JSONObject result=PortableProfileLifecycle.execute(plan.getString("token"));
   boolean success=mode.equals("healthy")||mode.equals("refresh-success");
   check(result.optBoolean("verified",false)==success,"Only exact fresh inventory verifies: "+mode);
   check(result.optBoolean("reviewRequired",false)!=success,"Read failure remains unknown");
   check(nativeCalls==1,"Exactly one native operation; no retry/rollback");
   check(!result.optBoolean("rollbackAttempted",false),"No automatic restoration write");
   check(CardTransactionLock.isQuarantined()!=mode.equals("healthy"),"Uncertain sessions retain exclusion");
   check(result.optBoolean("localSessionRestartRequired",false)==mode.equals("refresh-success"),"Only verified cleanup fault requests automatic retirement");
   if(mode.equals("native-worker"))check(reads==1,"An unconfirmed native worker cannot enter postflight");
   else if(mode.equals("card-swap"))check(discoveries>=2&&reads==1,"Changed card is rejected before inventory read");
   else check(reads>=2,"Fresh postflight is reached after returned REFRESH cleanup failure");
   check(CardTransactionLock.isActive(),"Outer journal exclusion remains held through result");
  }
  CardTransactionLock.endCoordinated();
  check(CardTransactionLock.isActive()==CardTransactionLock.isQuarantined(),"Only quarantined transactions retain lock ownership");
  System.out.println("PASS "+checks+" actual lifecycle entry/lock checks: "+mode);
 }
}
final class FiveberReaderSelector {
 static final class Target {String reader="SIM2",identityHash=LifecycleRefreshEntryTest.CARD;}
 static Target discover(){LifecycleRefreshEntryTest.discoveries++;Target t=new Target();if(LifecycleRefreshEntryTest.executed&&LifecycleRefreshEntryTest.mode.equals("card-swap"))t.identityHash="b".repeat(64);return t;}
}
final class SystemHalAdapter {
 static final FakeHal hal=new FakeHal();
 static SecureElementTransport connectForLifecycle(String r,ProfileLifecycleGuard.Operation op,String id,String name){return hal;}
 static final class FakeHal implements SecureElementTransport {
  int closed,frames;public boolean isCardPresent(){return true;}public int openLogicalChannel(byte[] aid,byte p2){return 2;}
  public byte[] transmit(byte[] command){frames++;return new byte[]{(byte)0x90,0};}
  public void closeChannel(int channel){LifecycleRefreshEntryTest.check(channel==2,"Close only owned channel");closed++;throw new SecurityException("Private message must not escape");}
 }
}
final class PhoneProfileLifecycle {
 static final class Outcome {boolean submitted,refreshCleanupFailed;}
 static void execute(ProfileLifecycleGuard.Operation o,String id,String name,String r,String c,Outcome out){
  LifecycleRefreshEntryTest.nativeCalls++;LifecycleRefreshEntryTest.executed=true;out.submitted=true;
  out.refreshCleanupFailed=!LifecycleRefreshEntryTest.mode.equals("healthy");
  if(LifecycleRefreshEntryTest.mode.equals("native-worker")){CardTransactionLock.quarantine();return;}
  if(!LifecycleRefreshEntryTest.mode.equals("unchanged"))LifecycleRefreshEntryTest.inventory=List.of(new CardProfileInventory.Entry(true));
 }
}
final class CardProfileInventory {
 static final class Entry {String iccid=LifecycleRefreshEntryTest.ID,nickname="",provider="Provider",name="Test";boolean enabled;Entry(boolean v){enabled=v;}}
 static List<Entry> list(){
  LifecycleRefreshEntryTest.reads++;
  if(LifecycleRefreshEntryTest.executed&&LifecycleRefreshEntryTest.mode.equals("postflight-worker")){
   long until=System.nanoTime()+300_000_000L;while(System.nanoTime()<until){try{Thread.sleep(10);}catch(InterruptedException ignored){}}
  }
  return LifecycleRefreshEntryTest.inventory;
 }
}
final class PhoneNativeLpac {static void listProfiles(FiveberReaderSelector.Target card){}}
final class EnrolledWritePolicy {static boolean isAllowed(String c){return true;}}
final class LabSupport {static void validateMutation(String c,ProfileLifecycleGuard.Operation o,List<ProfileLifecycleGuard.Row> r,String t){}}
final class ShellOperationScope {static void validateMutation(String c,ProfileLifecycleGuard.Operation o,List<ProfileLifecycleGuard.Row> r,String t){}}
final class NativeLpacBundle {static void ensureInstalled(){}}
final class SystemSubscriptionObservation {static JSONObject observe(String t){return new JSONObject().put("systemSubscriptionStatus","matched").put("systemSubscriptionWorkerStopped",true);}}
final class PhoneNotificationDelivery {static Set<Long> beforeDeletion(String r,String c,String id){throw new AssertionError();}static JSONObject afterDeletion(String r,String c,String id,Set<Long> b){throw new AssertionError();}}
'''
json_stub = r'''
package org.json;
import java.util.*;
public class JSONObject {
 private final Map<String,Object> values=new HashMap<>();
 public JSONObject put(String k,Object v){values.put(k,v);return this;}
 public String getString(String k){return (String)values.get(k);}
 public JSONObject getJSONObject(String k){return (JSONObject)values.get(k);}
 public boolean isNull(String k){return values.get(k)==null;}
 public String optString(String k){return optString(k,"");}
 public String optString(String k,String d){return values.get(k) instanceof String?(String)values.get(k):d;}
 public boolean optBoolean(String k,boolean d){return values.get(k) instanceof Boolean?(Boolean)values.get(k):d;}
 public boolean getBoolean(String k){return (Boolean)values.get(k);}
 public boolean has(String k){return values.containsKey(k);}
 public Object get(String k){return values.get(k);}
}
'''
with tempfile.TemporaryDirectory() as tmp:
    dest=Path(tmp)
    for name in ['PathSafety','ProfileLifecycleGuard','IntentDigests','OperationJournal','SecureElementTransport','ReaderDescriptor','SecureElementFailure','ScopedLifecycleHalBridge','PortableProfileLifecycle','LifecyclePostflight','DownloadWorkerSupervisor','LifecycleObservedOutcome','CardTransactionLock']:
        s=(src/(name+'.java')).read_text()
        if name=='CardTransactionLock':s=s.replace('new File("/data/local/tmp/fiveber-lpac-lab")','new File(System.getProperty("fixture.root"))')
        if name=='PortableProfileLifecycle':s=s.replace('Thread::sleep','millis->{}').replace('25_000L,3_000L','LifecycleRefreshEntryTest.mode.equals("postflight-worker")?20L:1000L,20L')
        (dest/(name+'.java')).write_text(s)
    (dest/'LifecycleRefreshEntryTest.java').write_text(fixture)
    (dest/'JSONObject.java').write_text(json_stub)
    for path,body in {
        'android/os/Build.java':'package android.os; public class Build {}',
        'android/os/Process.java':'package android.os; public class Process {public static final int SHELL_UID=2000;public static int myUid(){return 2000;}}',
        'android/os/SystemClock.java':'package android.os; public class SystemClock {public static long elapsedRealtime(){return System.nanoTime()/1000000;}}',
    }.items():
        p=dest/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body)
    java=Path(os.environ.get('JAVA_HOME','/usr'))/'bin'
    subprocess.run([str(java/'javac'),'-d',tmp,*map(str,dest.rglob('*.java'))],check=True)
    for scenario in ['bridge-refresh','bridge-rename','bridge-before-submit','healthy','refresh-success','unchanged','card-swap','native-worker','postflight-worker']:
        subprocess.run([str(java/'java'),'-Dfixture.root='+str(dest/scenario),'-cp',tmp,'dev.sid.fiveberinspector.LifecycleRefreshEntryTest',scenario],check=True)

    # Also exercise the actual native protocol runner/finally composition. The
    # child is a disposable fixture process, never a device helper or real lpac.
    native_fixture=fixture[:fixture.index('final class PhoneProfileLifecycle {')]+fixture[fixture.index('final class CardProfileInventory {'):]
    native_fixture=native_fixture.replace('if(mode.startsWith("bridge-"))bridge();', '''if(mode.startsWith("native-")){
   PhoneProfileLifecycle.Outcome outcome=new PhoneProfileLifecycle.Outcome();boolean failed=false;
   try{PhoneProfileLifecycle.execute(ProfileLifecycleGuard.Operation.ENABLE,ID,"","SIM2",CARD,outcome);}catch(Exception expected){failed=true;}
   check(failed==!mode.equals("native-success"),"Native result/exit code still checked");
   check(outcome.submitted,"Native runner retains exact submission evidence on failure");
   check(outcome.refreshCleanupFailed,"Native runner forwards deferred cleanup from its finally block");
   check(!CardTransactionLock.isQuarantined(),"Returned refresh close error reaches independent postflight before quarantine");
   check(SystemHalAdapter.hal.closed==1&&SystemHalAdapter.hal.frames==1,"One native command and one owned-channel close");
  }
  else if(mode.startsWith("bridge-"))bridge();''')
    protocol_json=json_stub.replace(' private final Map<String,Object>', ''' public JSONObject(){}
 public JSONObject(String frame){
  if(frame.equals("RESULT")){put("type","lpa");put("payload",new JSONObject().put("code",0));return;}
  String fn=frame.equals("OPEN")?"logic_channel_open":frame.equals("TX")?"transmit":"logic_channel_close";
  String param=frame.equals("OPEN")?"A0000005591010FFFFFFFF8900000100":null;
  if(frame.equals("TX")){
   byte[] bytes=dev.sid.fiveberinspector.ProfileLifecycleGuard.command(dev.sid.fiveberinspector.ProfileLifecycleGuard.Operation.ENABLE,"1234567890123456789","",2);
   StringBuilder hex=new StringBuilder();for(byte b:bytes)hex.append(String.format("%02X",b&255));param=hex.toString();
  }
  put("type","apdu");put("payload",new JSONObject().put("func",fn).put("param",param));
 }
 public int optInt(String k,int d){return values.get(k) instanceof Integer?(Integer)values.get(k):d;}
 private final Map<String,Object>''')
    (dest/'LifecycleRefreshEntryTest.java').write_text(native_fixture)
    (dest/'JSONObject.java').write_text(protocol_json)
    runner=(src/'PhoneProfileLifecycle.java').read_text().replace('"/data/local/tmp/fiveber-lpac-lab"','System.getProperty("fixture.root")')
    (dest/'PhoneProfileLifecycle.java').write_text(runner)
    subprocess.run([str(java/'javac'),'-d',tmp,*map(str,dest.rglob('*.java'))],check=True)
    for scenario in ['native-success','native-rejected']:
        runtime=dest/scenario;runtime.mkdir()
        child=runtime/'lpac'
        child.write_text('#!/bin/sh\necho OPEN\nread reply\necho TX\nread reply\necho CLOSE\nread reply\necho RESULT\nexit '+('0' if scenario=='native-success' else '1')+'\n')
        child.chmod(0o700)
        subprocess.run([str(java/'java'),'-Dfixture.root='+str(runtime),'-cp',tmp,'dev.sid.fiveberinspector.LifecycleRefreshEntryTest',scenario],check=True)
