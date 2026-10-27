#!/usr/bin/env python3
"""Execute production Shizuku bootstrap/connection code with controlled Android/process I/O.

No live Shizuku authorization, Binder handoff, phone connection or card access is claimed.
The process adapter runs no OS commands and records only fixed argument/environment values.
"""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
java_source = root / 'app/src/main/java/dev/sid/fiveberinspector'
sources = {
'android/content/Context.java': 'package android.content; public class Context {}',
'android/content/ComponentName.java': 'package android.content; public class ComponentName {public ComponentName(Context c,Class<?> k){if(!k.getName().endsWith(".ShizukuBootstrapService"))throw new AssertionError();}}',
'android/content/ServiceConnection.java': 'package android.content; public interface ServiceConnection {void onServiceConnected(ComponentName n,android.os.IBinder b);void onServiceDisconnected(ComponentName n);}',
'android/content/pm/PackageManager.java': 'package android.content.pm; public class PackageManager {public static final int PERMISSION_GRANTED=0;}',
'android/os/IBinder.java': 'package android.os; public interface IBinder {boolean pingBinder();String getInterfaceDescriptor();}',
'android/os/Process.java': 'package android.os; public class Process {public static final int SHELL_UID=2000;public static int uid=2000;public static int myUid(){return uid;}}',
'android/os/SystemClock.java': 'package android.os; public class SystemClock {public static long now;public static long elapsedRealtime(){return now;}}',
'android/os/Binder.java': 'package android.os; public class Binder {public static int uid=10501;public static int getCallingUid(){return uid;}}',
'rikka/shizuku/Shizuku.java': r'''package rikka.shizuku;
import android.content.*;import android.os.*;
public class Shizuku {
 public static boolean ping=true,pre,deny,revoke,bindThrow,noEvent;public static int version=13,uid=2000,permission,binds,unbinds,requests;
 public static IBinder binder;public static UserServiceArgs last;
 public static boolean pingBinder(){if(deny)throw new IllegalStateException();return ping;}
 public static boolean isPreV11(){return pre;}public static int getVersion(){return version;}public static int getUid(){return uid;}
 public static int checkSelfPermission(){return permission;}public static void requestPermission(int code){if(code!=7313)throw new AssertionError();requests++;}
 public static class UserServiceArgs {
  public boolean daemon=true,debug=true;public int version;public String tag,suffix;
  public UserServiceArgs(ComponentName n){}public UserServiceArgs daemon(boolean v){daemon=v;return this;}
  public UserServiceArgs debuggable(boolean v){debug=v;return this;}public UserServiceArgs tag(String v){tag=v;return this;}
  public UserServiceArgs version(int v){version=v;return this;}public UserServiceArgs processNameSuffix(String v){suffix=v;return this;}
 }
 public static void bindUserService(UserServiceArgs a,ServiceConnection c){last=a;binds++;if(bindThrow)throw new IllegalStateException();
  if(revoke)permission=1;if(!noEvent){if(binder==null)c.onServiceDisconnected(null);else c.onServiceConnected(null,binder);}}
 public static void unbindUserService(UserServiceArgs a,ServiceConnection c,boolean remove){if(!remove)throw new AssertionError();unbinds++;}
}
''',
'dev/sid/fiveberinspector/IShizukuBootstrap.java': r'''package dev.sid.fiveberinspector;
interface IShizukuBootstrap {boolean connect();void destroy();
 abstract class Stub implements IShizukuBootstrap,android.os.IBinder {
  static final String DESCRIPTOR="dev.sid.fiveberinspector.IShizukuBootstrap";
  static IShizukuBootstrap asInterface(android.os.IBinder b){return (IShizukuBootstrap)b;}
  public boolean pingBinder(){return true;}public String getInterfaceDescriptor(){return DESCRIPTOR;}
 }
}
''',
'dev/sid/fiveberinspector/TestEnv.java': r'''package dev.sid.fiveberinspector;
import java.util.*;import java.io.*;import java.util.concurrent.*;
class TestEnv {static int exits;static void exit(int code){exits++;}}
class BuildConfig {static final int VERSION_CODE=54;}
class CompanionBinderProvider {static Object management;static int windows;static Object management(){return management;}static void beginManagementBootstrap(){if(management!=null)throw new SecurityException();windows++;}}
class NativeLpacBundle {static String path="/data/app/~~fixture/dev.sid.fiveberinspector-fixture/base.apk";static String installedApk(){return path;}}
class ServiceCaller {
 static boolean allowed=true;static int calls,pins;
 static void pinInstallation(){pins++;if(android.os.Process.myUid()!=2000)throw new SecurityException();}
 static void requireApp(){calls++;pinInstallation();if(!allowed||android.os.Binder.getCallingUid()!=10501)throw new SecurityException();}
 static ShellCall asShell(){requireApp();return new ShellCall();}static class ShellCall implements AutoCloseable{public void close(){}}
}
class ProcessBuilder {
 static final List<List<String>> calls=new ArrayList<>();static final List<Map<String,String>> envs=new ArrayList<>();
 static FakeProcess next;final Map<String,String> env=new HashMap<>();
 ProcessBuilder(String...args){calls.add(List.of(args));envs.add(env);}
 Map<String,String> environment(){return env;}
 ProcessBuilder redirectInput(File f){checkSink(f);return this;}ProcessBuilder redirectOutput(File f){checkSink(f);return this;}ProcessBuilder redirectError(File f){checkSink(f);return this;}
 static void checkSink(File f){if(!f.getPath().equals("/dev/null"))throw new AssertionError("No raw output permitted");}
 java.lang.Process start(){return next;}
}
class FakeProcess extends java.lang.Process {
 boolean waited=true,alive;int result,killed;
 public OutputStream getOutputStream(){return OutputStream.nullOutputStream();}public InputStream getInputStream(){return InputStream.nullInputStream();}public InputStream getErrorStream(){return InputStream.nullInputStream();}
 public int waitFor(){return result;}public boolean waitFor(long n,TimeUnit unit){if(n!=40||unit!=TimeUnit.SECONDS)throw new AssertionError("Bounded bootstrap wait");return waited;}
 public int exitValue(){return result;}public boolean isAlive(){return alive;}public void destroy(){killed++;alive=false;}public java.lang.Process destroyForcibly(){destroy();return this;}
}
''',
'dev/sid/fiveberinspector/ShizukuConnectionTest.java': r'''package dev.sid.fiveberinspector;
import android.content.Context;import rikka.shizuku.Shizuku;
public class ShizukuConnectionTest {
 static int checks;static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
 static void reset(){Shizuku.ping=true;Shizuku.pre=false;Shizuku.deny=false;Shizuku.revoke=false;Shizuku.bindThrow=false;Shizuku.noEvent=false;Shizuku.version=13;Shizuku.uid=2000;Shizuku.permission=0;Shizuku.binds=0;Shizuku.unbinds=0;Shizuku.binder=new FixtureBinder();CompanionBinderProvider.management=new Object();}
 static class FixtureBinder extends IShizukuBootstrap.Stub {boolean alive=true,result=true,fail;String descriptor=DESCRIPTOR;int calls;
  public boolean pingBinder(){return alive;}public String getInterfaceDescriptor(){return descriptor;}
  public boolean connect(){calls++;if(fail)throw new IllegalStateException();return result;}public void destroy(){}
 }
 static boolean connect()throws Exception{return ShizukuConnection.connect(new Context());}
 public static void main(String[] args)throws Exception{
  reset();check(ShizukuConnection.available()&&ShizukuConnection.permitted(),"Running Shell Shizuku is usable");
  Shizuku.uid=0;check(!ShizukuConnection.available()&&!connect()&&Shizuku.binds==0,"Root backend cannot bind or launch");
  reset();Shizuku.uid=10001;check(!ShizukuConnection.available(),"Ordinary app identity rejected");
  reset();Shizuku.ping=false;check(!ShizukuConnection.available()&&!connect(),"Stopped server never offered");
  reset();Shizuku.pre=true;check(!ShizukuConnection.available(),"Pre-v11 server rejected");
  reset();Shizuku.version=12;check(!ShizukuConnection.available(),"Unsupported server version rejected");
  reset();Shizuku.deny=true;check(!ShizukuConnection.available(),"Dead server category handled without raw message");
  reset();Shizuku.permission=1;check(!ShizukuConnection.permitted()&&!connect()&&Shizuku.binds==0,"No permission means no bootstrap");
  reset();check(connect(),"Only live management handoff completes connection");
  check(!Shizuku.last.daemon&&!Shizuku.last.debug&&Shizuku.last.version==BuildConfig.VERSION_CODE,"Launcher is non-daemon, release-safe and versioned");
  check(Shizuku.last.tag.equals("8bercue-fixed-bootstrap")&&Shizuku.last.suffix.equals("bootstrap"),"Fixed service identity");
  check(Shizuku.unbinds==1,"Successful launcher removed independently of backend");
  reset();CompanionBinderProvider.management=null;check(!connect(),"Launch success cannot impersonate management handoff");
  reset();FixtureBinder b=(FixtureBinder)Shizuku.binder;b.result=false;check(!connect()&&Shizuku.unbinds==1,"Failed fixed launch cleaned up");
  reset();b=(FixtureBinder)Shizuku.binder;b.alive=false;check(!connect()&&b.calls==0,"Dead bootstrap Binder not invoked");
  reset();b=(FixtureBinder)Shizuku.binder;b.descriptor="foreign";check(!connect()&&b.calls==0,"Foreign interface not invoked");
  reset();Shizuku.binder=null;check(!connect()&&Shizuku.unbinds==1,"Disconnected bind fails closed and cleans up");
  reset();Shizuku.revoke=true;b=(FixtureBinder)Shizuku.binder;check(!connect()&&b.calls==0,"Permission rechecked after asynchronous bind");
  reset();Shizuku.bindThrow=true;try{connect();throw new AssertionError();}catch(IllegalStateException expected){check(Shizuku.unbinds==1,"Bind failure still detaches own launcher");}
  reset();b=(FixtureBinder)Shizuku.binder;b.fail=true;try{connect();throw new AssertionError();}catch(IllegalStateException expected){check(Shizuku.unbinds==1,"Remote failure still detaches own launcher");}
  reset();Shizuku.noEvent=true;Thread.currentThread().interrupt();try{connect();throw new AssertionError();}catch(InterruptedException expected){check(Shizuku.unbinds==1,"Cancellation detaches pending launcher");}
  android.os.Process.uid=0;try{new ShizukuBootstrapService();throw new AssertionError();}catch(SecurityException expected){check(true,"Actual launcher constructor rejects root");}
  android.os.Process.uid=2000;android.os.Binder.uid=10501;ServiceCaller.allowed=false;
  ShizukuBootstrapService launcher=new ShizukuBootstrapService();ProcessBuilder.calls.clear();check(!launcher.connect()&&ProcessBuilder.calls.isEmpty(),"Foreign caller cannot launch a process");
  ServiceCaller.allowed=true;android.os.Binder.uid=10002;check(!launcher.connect()&&ProcessBuilder.calls.isEmpty(),"Kernel Binder caller must match app");
  android.os.Binder.uid=10501;ProcessBuilder.next=new FakeProcess();check(launcher.connect(),"Authorized one-use launcher executes fixed Connect");
  check(ProcessBuilder.calls.equals(java.util.List.of(java.util.List.of("/system/bin/app_process","/","dev.sid.fiveberinspector.Connect"))),"No shell, path, profile or APDU input surface");
  check(ProcessBuilder.envs.get(0).get("CLASSPATH").equals(NativeLpacBundle.path),"Current package-owned APK resolved inside Shell");
  check(!launcher.connect()&&ProcessBuilder.calls.size()==1,"Same bootstrap service cannot execute twice");
  launcher=new ShizukuBootstrapService();ProcessBuilder.next=new FakeProcess();ProcessBuilder.next.waited=false;ProcessBuilder.next.alive=true;
  check(!launcher.connect()&&ProcessBuilder.next.killed==1,"Timeout retires only owned bootstrap client");
  launcher=new ShizukuBootstrapService();ProcessBuilder.next=new FakeProcess();ProcessBuilder.next.result=1;check(!launcher.connect(),"Connect failure never reported as success");
  launcher=new ShizukuBootstrapService();NativeLpacBundle.path="/sdcard/helper.apk";int before=ProcessBuilder.calls.size();check(!launcher.connect()&&ProcessBuilder.calls.size()==before,"Invalid package path cannot launch");
  android.os.Binder.uid=10002;try{launcher.destroy();throw new AssertionError();}catch(SecurityException expected){check(TestEnv.exits==0,"Foreign destroy caller rejected");}
  android.os.Binder.uid=2000;launcher.destroy();check(TestEnv.exits==1,"Reserved destroy belongs to Shell service");
  android.os.Process.uid=0;try{launcher.destroy();throw new AssertionError();}catch(SecurityException expected){check(TestEnv.exits==1,"Root destroy cannot enter Shell contract");}
  System.out.println("PASS "+checks+" actual Shizuku connection/launcher checks (controlled Binder/process I/O, no live authorization)");
 }
}
'''
}
ui = (java_source / 'MainActivity.java').read_text()
methods = ui[ui.index('  private void chooseShizukuConnection()'):ui.index('  private void showConnectionHelp()')]
listener = ui[ui.index('    if(request!=ShizukuConnection.PERMISSION_REQUEST'):ui.index('  private final Runnable watch')].rsplit('  });', 1)[0]
names = sorted(set(__import__('re').findall(r'R\.string\.(\w+)', methods + listener)))
resources = ';'.join('static final int ' + name + '=' + str(i) for i, name in enumerate(names)) + ';'
sources['dev/sid/fiveberinspector/ShizukuConsentTest.java'] = r'''package dev.sid.fiveberinspector;
import android.content.*;import android.content.pm.PackageManager;import java.util.*;
public class ShizukuConsentTest {
 static class R {static class string {RESOURCES}}
 static int checks;static void check(boolean v,String message){checks++;if(!v)throw new AssertionError(message);}
 static class JSONObject {Map<String,Object> values=new HashMap<>();JSONObject put(String k,Object v){values.put(k,v);return this;}boolean optBoolean(String k){return Boolean.TRUE.equals(values.get(k));}}
 static class OPERATIONS {static boolean busy;static boolean running(){return busy;}}
 static class CompatibilityDiagnostics {enum Transport {ADB_COMPANION}static void started(Transport t){}}
 interface Job {JSONObject run()throws Exception;}interface Done {void accept(JSONObject v);}
 static class Entry {
  boolean destroyed,resumed=true,recoveryReconnectNeeded,shizukuPermissionPending,fallback,binderReadOnlyInventory,directManagement,companionManagement;
  long shizukuPermissionUntil,navigationSession;Object backend;JSONObject compatibility;int renders,jobs,recoveries;String title,detail;
  String s(int value){return Integer.toString(value);}void render(){renders++;}void status(String t,String d){title=t;detail=d;}
  void rememberConnectionMethod(String method){}void reconnectVerifiedRefresh(){recoveries++;}
  Context getApplicationContext(){return new Context();}void clearDetailSelection(){}
  void connectDirect(){backend=CompanionBinderProvider.management();}
  void run(String label,boolean mutation,Job work,Done done){if(mutation)throw new AssertionError();jobs++;try{done.accept(work.run());}catch(Exception failure){throw new AssertionError(failure);}}
METHODS
  void permissionResult(int request,int grant){
LISTENER
  }
 }
 static void reset(){ShizukuConnectionTest.reset();rikka.shizuku.Shizuku.permission=1;rikka.shizuku.Shizuku.requests=0;CompanionBinderProvider.management=null;CompanionBinderProvider.windows=0;android.os.SystemClock.now=100;OPERATIONS.busy=false;}
 public static void main(String[] args){
  reset();Entry e=new Entry();e.chooseShizukuConnection();check(rikka.shizuku.Shizuku.requests==1&&e.jobs==0&&CompanionBinderProvider.windows==0,"Only chosen route requests permission; no backend before consent");
  e.chooseShizukuConnection();check(rikka.shizuku.Shizuku.requests==1,"Repeated tap cannot request duplicate permission dialogs");
  e.permissionResult(999,0);check(e.shizukuPermissionPending&&e.jobs==0,"Unrelated permission result cannot authorize");
  e.permissionResult(ShizukuConnection.PERMISSION_REQUEST,1);check(!e.shizukuPermissionPending&&e.jobs==0,"Denial cannot launch or leave deferred consent");
  reset();e=new Entry();e.chooseShizukuConnection();rikka.shizuku.Shizuku.permission=0;e.resumed=false;e.permissionResult(ShizukuConnection.PERMISSION_REQUEST,0);
  check(e.shizukuPermissionPending&&e.jobs==0,"Background permission callback cannot launch");
  e.resumed=true;e.resumeShizukuAuthorization();check(!e.shizukuPermissionPending&&e.jobs==1&&CompanionBinderProvider.windows==1,"Foreground resumes the explicit unexpired consent exactly once");
  e.resumeShizukuAuthorization();check(e.jobs==1,"Repeated resume cannot repeat bootstrap");
  reset();e=new Entry();e.chooseShizukuConnection();rikka.shizuku.Shizuku.permission=0;android.os.SystemClock.now=120101;e.resumeShizukuAuthorization();check(!e.shizukuPermissionPending&&e.jobs==0,"Expired permission intent cannot authorize a future session");
  reset();e=new Entry();e.chooseShizukuConnection();e.destroyed=true;rikka.shizuku.Shizuku.permission=0;e.permissionResult(ShizukuConnection.PERMISSION_REQUEST,0);check(e.jobs==0,"Destroyed Activity cannot consume authorization callback");
  reset();e=new Entry();rikka.shizuku.Shizuku.permission=0;OPERATIONS.busy=true;e.chooseShizukuConnection();check(e.jobs==0&&rikka.shizuku.Shizuku.requests==0,"Active card transaction cannot change privilege method");
  reset();e=new Entry();e.backend=new Object();e.chooseShizukuConnection();check(e.jobs==0&&rikka.shizuku.Shizuku.requests==0,"Existing session cannot be replaced by a new method");
  reset();e=new Entry();rikka.shizuku.Shizuku.uid=0;e.chooseShizukuConnection();check(e.jobs==0&&rikka.shizuku.Shizuku.requests==0,"Root mode never requests new privilege");
  reset();e=new Entry();e.resumed=false;e.chooseShizukuConnection();check(!e.shizukuPermissionPending&&e.jobs==0,"Inactive Activity cannot create permission intent");
  reset();e=new Entry();rikka.shizuku.Shizuku.permission=0;e.chooseShizukuConnection();check(e.jobs==1&&CompanionBinderProvider.windows==1,"Existing permission still requires a fresh explicit App challenge");
  check(e.title.equals(e.s(R.string.ui_connection_not_completed)),"No management Binder means visible connection failure");
  System.out.println("PASS "+checks+" actual Shizuku authorization entrypoint checks (controlled lifecycle/permission I/O)");
 }
}'''.replace('RESOURCES', resources).replace('METHODS', methods).replace('LISTENER', listener)
for name in ['ShizukuConnection', 'ShizukuBootstrapService', 'ConnectionCommand']:
    source = (java_source / (name + '.java')).read_text()
    # Exit is the process-lifetime adapter; production System.exit is never executed here.
    if name == 'ShizukuBootstrapService':
        source = source.replace('System.exit(0)', 'TestEnv.exit(0)')
    sources['dev/sid/fiveberinspector/' + name + '.java'] = source
java = Path(os.environ.get('JAVA_HOME', '/usr')) / 'bin'
with tempfile.TemporaryDirectory() as tmp:
    files = []
    for name, content in sources.items():
        file = Path(tmp) / name
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(content)
        files.append(str(file))
    subprocess.run([str(java / 'javac'), '-d', tmp, *files], check=True)
    subprocess.run([str(java / 'java'), '-cp', tmp, 'dev.sid.fiveberinspector.ShizukuConnectionTest'], check=True)
    subprocess.run([str(java / 'java'), '-cp', tmp, 'dev.sid.fiveberinspector.ShizukuConsentTest'], check=True)
