#!/usr/bin/env python3
"""Actual TLS bootstrap entry with controlled discovery/Binder I/O, no live ADB."""
from pathlib import Path
import os, subprocess, tempfile

root=Path(__file__).resolve().parents[1]
bootstrap=root/'app/src/release/java/dev/sid/fiveberinspector/LocalAdbBootstrap.java'
fixture=r'''
package dev.sid.fiveberinspector;
import android.content.Context;
import java.io.IOException;
public class VerifiedRefreshConnectionTest {
 static int checks;
 static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 static void reset(){CompanionBinderProvider.binder=new Object();OnDeviceAdbWirelessManager.paired=true;OnDeviceAdbWirelessManager.fail=false;OnDeviceAdbWirelessManager.starts=OnDeviceAdbWirelessManager.restarts=0;OnDeviceAdbDiscovery.missing=false;OnDeviceAdbDiscovery.closes=0;}
 public static void main(String[] args){
  Context c=new Context();reset();
  check(LocalAdbBootstrap.start(c).ready,"Normal connection reuses existing authenticated Binder");
  check(OnDeviceAdbWirelessManager.restarts==0,"Normal connect never retires a session");
  Object old=CompanionBinderProvider.binder;
  check(LocalAdbBootstrap.restartAfterVerifiedRefresh(c).ready,"Verified REFRESH explicitly enters recovery with an existing Binder");
  check(CompanionBinderProvider.binder!=old&&OnDeviceAdbWirelessManager.restarts==1,"Recovery does not short-circuit to the stale Binder");
  check(OnDeviceAdbWirelessManager.starts==0&&OnDeviceAdbDiscovery.closes==1,"One bounded recovery, discovery closed");
  reset();OnDeviceAdbWirelessManager.paired=false;
  check(!LocalAdbBootstrap.restartAfterVerifiedRefresh(c).ready,"No silent pairing or new identity");
  check(OnDeviceAdbWirelessManager.restarts==0,"Missing identity performs no start");
  reset();OnDeviceAdbWirelessManager.fail=true;old=CompanionBinderProvider.binder;
  check(!LocalAdbBootstrap.restartAfterVerifiedRefresh(c).ready,"Failed retirement never claims existing Binder is recovered");
  check(CompanionBinderProvider.binder==old&&OnDeviceAdbWirelessManager.restarts==1,"No retry or bypass after recovery fails");
  check(OnDeviceAdbDiscovery.closes==1,"Failure closes discovery");
  reset();OnDeviceAdbDiscovery.missing=true;
  check(!LocalAdbBootstrap.restartAfterVerifiedRefresh(c).ready&&OnDeviceAdbWirelessManager.restarts==0,"Missing own connection service performs no start");
  reset();CompanionBinderProvider.binder=null;
  check(LocalAdbBootstrap.start(c).ready&&OnDeviceAdbWirelessManager.starts==1&&OnDeviceAdbWirelessManager.restarts==0,"Ordinary fresh bootstrap remains unchanged");
  System.out.println("PASS "+checks+" actual verified-refresh TLS bootstrap checks (controlled discovery/Binder I/O)");
 }
}
final class CompanionBinderProvider {static Object binder;static Object management(){return binder;}}
final class OnDeviceAdbWirelessManager {
 static boolean paired,fail;static int starts,restarts;
 static boolean hasPaired(Context c){return paired;}
 static void startManagement(Context c,OnDeviceAdbDiscovery.Endpoint e){starts++;CompanionBinderProvider.binder=new Object();}
 static void restartManagement(Context c,OnDeviceAdbDiscovery.Endpoint e)throws IOException{restarts++;if(fail)throw new IOException("Private failure detail");CompanionBinderProvider.binder=new Object();}
}
final class OnDeviceAdbDiscovery {
 enum Purpose{CONNECTION}static final class Endpoint{}
 interface Observer {void found(Endpoint e);void lost(Purpose p);void failed(Purpose p);}
 static boolean missing;static int closes;final Observer observer;
 OnDeviceAdbDiscovery(Context c,Purpose p,Observer o){observer=o;}
 void start(){if(missing)observer.failed(Purpose.CONNECTION);else observer.found(new Endpoint());}
 void close(){closes++;}
}
'''
with tempfile.TemporaryDirectory() as tmp:
    dest=Path(tmp)
    (dest/'LocalAdbBootstrap.java').write_text(bootstrap.read_text())
    (dest/'VerifiedRefreshConnectionTest.java').write_text(fixture)
    for path,body in {
        'android/content/Context.java':'package android.content; public class Context {}',
        'android/os/Build.java':'package android.os; public class Build {public static class VERSION {public static final int SDK_INT=37;}}',
        'android/util/Log.java':'package android.util; public class Log {public static int w(String tag,String message){return 0;}}',
    }.items():
        p=dest/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body)
    java=Path(os.environ.get('JAVA_HOME','/usr'))/'bin'
    subprocess.run([str(java/'javac'),'-d',tmp,*map(str,dest.rglob('*.java'))],check=True)
    subprocess.run([str(java/'java'),'-cp',tmp,'dev.sid.fiveberinspector.VerifiedRefreshConnectionTest'],check=True)
