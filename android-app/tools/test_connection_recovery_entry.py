#!/usr/bin/env python3
"""Execute actual selected-method recovery routing with controlled bootstrap/Binder I/O.

No live authorization, TLS connection, process retirement or card write is claimed.
"""
import os
from pathlib import Path
import re
import subprocess
import tempfile
root = Path(__file__).resolve().parents[1]
ui = (root/'app/src/main/java/dev/sid/fiveberinspector/MainActivity.java').read_text()
methods = ui[ui.index('  private boolean needsVerifiedSessionRestart('):ui.index('  private void home()')]
names = sorted(set(re.findall(r'R\.string\.(\w+)',methods)))
resources=';'.join(f'static final int {name}={i}' for i,name in enumerate(names))+';'
source=r'''
import java.util.*;
public class ConnectionRecoveryEntryTest {
 static int checks;static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
 static class R {static class string {RESOURCES}}
 static class SettingsNavigation {static final int CONNECTION=7;}
 static class JSONObject {
  Map<String,Object> values=new HashMap<>();JSONObject put(String k,Object v){values.put(k,v);return this;}
  boolean optBoolean(String k){return optBoolean(k,false);}boolean optBoolean(String k,boolean fallback){return values.getOrDefault(k,fallback).equals(true);}
  String optString(String k,String fallback){return (String)values.getOrDefault(k,fallback);}
 }
 static class OPERATIONS {static boolean busy;static boolean running(){return busy;}}
 static class OnDeviceAdbWirelessManager {static boolean paired;static boolean hasPaired(Object c){return paired;}}
 static class LocalAdbBootstrap {
  static int starts,restarts;static boolean ready=true;static class Result {boolean ready;String description="fixed category";}
  static Result start(Object c){starts++;Result r=new Result();r.ready=ready;return r;}
  static Result restartAfterVerifiedRefresh(Object c){restarts++;Result r=new Result();r.ready=ready;return r;}
 }
 static class ShizukuConnection {
  static boolean allowed=true,ready=true;static int connects;
  static boolean permitted(){return allowed;}static boolean connect(Object c){connects++;return ready;}
 }
 static class CompanionBinderProvider {
  static Object binder;static int fresh,recovery;static Object management(){return binder;}
  static void beginManagementBootstrap(){fresh++;}static void beginManagementRecoveryBootstrap(){recovery++;}
 }
 interface Job {JSONObject run()throws Exception;}interface Done {void accept(JSONObject result);}
 static class Entry {
  boolean destroyed,resumed=true,recoveryReconnectNeeded,wireless=true;String connectionMethod="unknown";Object backend;
  int screen,jobs,renders,attaches,previews,computer;JSONObject compatibility;String detail;
  String s(int n){return Integer.toString(n);}boolean wirelessOnboardingAvailable(){return wireless;}
  void rememberConnectionMethod(String value){connectionMethod=value;}Object getApplicationContext(){return this;}
  void beginDirectBootstrap(){computer++;}void showWirelessDiscoveryPreview(){previews++;}
  void connectDirect(){attaches++;backend=new Object();}void render(){renders++;}
  void status(String title,String description){detail=description;}void statusPreservingOutcome(String title,String description){detail=description;}
  void run(String label,boolean mutation,Job job,Done done){jobs++;check(!mutation,"Recovery is not a card mutation");try{done.accept(job.run());}catch(Exception e){throw new AssertionError(e);}}
ACTUAL
 }
 static Entry entry(String method){
  OPERATIONS.busy=false;LocalAdbBootstrap.starts=0;LocalAdbBootstrap.restarts=0;LocalAdbBootstrap.ready=true;
  ShizukuConnection.allowed=true;ShizukuConnection.ready=true;ShizukuConnection.connects=0;
  CompanionBinderProvider.binder=new Object();CompanionBinderProvider.fresh=0;CompanionBinderProvider.recovery=0;
  OnDeviceAdbWirelessManager.paired=true;
  Entry e=new Entry();e.connectionMethod=method;e.recoveryReconnectNeeded=true;return e;
 }
 static void noLaunch(Entry e,String why){check(e.jobs==0&&LocalAdbBootstrap.restarts==0&&ShizukuConnection.connects==0&&e.attaches==0,why);}
 public static void main(String[] args){
  Entry e=new Entry();
  for(boolean flag:new boolean[]{false,true})for(boolean verified:new boolean[]{false,true})for(boolean review:new boolean[]{false,true})
   check(e.needsVerifiedSessionRestart(new JSONObject().put("localSessionRestartRequired",flag),verified,review)==(flag&&verified&&!review),"Automatic restart requires exact verified result, no review, and explicit cleanup flag");
  e=entry("computer");e.reconnectVerifiedRefresh();noLaunch(e,"Computer selection never silently uses old wireless pairing or Shizuku grant");
  check(e.screen==7&&e.recoveryReconnectNeeded&&e.detail.equals(e.s(R.string.nav_reconnect_using_selected_method)),"Computer user gets an actionable command guide while quarantine is retained");
  e=entry("unknown");e.reconnectVerifiedRefresh();noLaunch(e,"Missing session mode does not guess an authorization route");
  e=entry("wireless");e.reconnectVerifiedRefresh();check(LocalAdbBootstrap.restarts==1&&ShizukuConnection.connects==0&&e.attaches==1,"Selected paired wireless method restarts once through TLS");
  e=entry("wireless");OnDeviceAdbWirelessManager.paired=false;e.reconnectVerifiedRefresh();noLaunch(e,"Missing pairing cannot fall back to another privilege manager");
  e=entry("wireless");e.wireless=false;e.reconnectVerifiedRefresh();noLaunch(e,"Missing native capability cannot trigger a false wireless attempt");
  e=entry("shizuku");e.reconnectVerifiedRefresh();check(ShizukuConnection.connects==1&&LocalAdbBootstrap.restarts==0&&CompanionBinderProvider.recovery==1&&e.attaches==1,"Selected authorized Shizuku uses one explicit recovery challenge and fixed launcher");
  e=entry("shizuku");CompanionBinderProvider.binder=null;e.reconnectVerifiedRefresh();check(CompanionBinderProvider.fresh==1&&CompanionBinderProvider.recovery==0&&e.attaches==1,"Gone old session requires a fresh bootstrap challenge");
  e=entry("shizuku");ShizukuConnection.allowed=false;e.reconnectVerifiedRefresh();noLaunch(e,"Revoked or stopped Shizuku cannot auto-launch, request permission, or fall back to TLS");
  check(e.recoveryReconnectNeeded&&e.screen==7,"Revoked authorization leaves recovery visible and exclusion intact");
  e=entry("shizuku");ShizukuConnection.ready=false;e.reconnectVerifiedRefresh();check(e.attaches==0&&e.recoveryReconnectNeeded&&e.screen==7&&ShizukuConnection.connects==1,"Failed Shizuku recovery remains blocked without retry");
  e.render();e.render();check(ShizukuConnection.connects==1,"Rendering cannot retry a failed bootstrap");
  e=entry("wireless");LocalAdbBootstrap.ready=false;e.reconnectVerifiedRefresh();check(e.attaches==0&&e.recoveryReconnectNeeded&&LocalAdbBootstrap.restarts==1,"Failed TLS recovery remains blocked without retry");
  e=entry("shizuku");e.resumed=false;e.reconnectVerifiedRefresh();noLaunch(e,"Background Activity cannot start recovery");
  e=entry("shizuku");e.destroyed=true;e.reconnectVerifiedRefresh();noLaunch(e,"Destroyed Activity cannot start recovery");
  e=entry("shizuku");OPERATIONS.busy=true;e.reconnectVerifiedRefresh();noLaunch(e,"Active transaction cannot be interrupted by recovery");
  e=entry("computer");e.primaryConnect();check(e.connectionMethod.equals("wireless")&&LocalAdbBootstrap.restarts==1,"An explicit wireless choice can change the recovery route");
  System.out.println("PASS "+checks+" actual selected-method recovery entry checks (controlled lifecycle/permission/bootstrap I/O)");
 }
}
'''.replace('RESOURCES',resources).replace('ACTUAL',methods)
java=Path(os.environ.get('JAVA_HOME','/usr'))/'bin'
with tempfile.TemporaryDirectory() as tmp:
 file=Path(tmp)/'ConnectionRecoveryEntryTest.java';file.write_text(source)
 subprocess.run([str(java/'javac'),'-d',tmp,str(file)],check=True)
 subprocess.run([str(java/'java'),'-cp',tmp,'ConnectionRecoveryEntryTest'],check=True)
