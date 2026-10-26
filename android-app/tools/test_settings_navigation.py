#!/usr/bin/env python3
"""Execute the actual connection-page and permission methods with display adapters.

These tests cover capability routing and explicit permission consent, not Android
rendering, notification delivery, real pairing or card access.
"""
import os
from pathlib import Path
import re
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
ui = (root / 'app/src/main/java/dev/sid/fiveberinspector/MainActivity.java').read_text()
page = ui[ui.index('  private void connectionAccessScreen()'):ui.index('  private void showConnectionHelp()')]
names = sorted(set(re.findall(r'R\.string\.(\w+)', page)))
resources = '\n'.join(f'static final int {name}={i};' for i, name in enumerate(names))
labels = ','.join('"' + name + '"' for name in names)
source = r'''
import java.util.*;
public class SettingsPageTest {
  static int checks;
  static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
  static final class R {static final class string {RESOURCES}}
  static final String[] LABELS={LABELS_VALUE};
  static final class LinearLayout {void addView(Object v){}void addView(Object v,Object p){}}
  static final class Build {static final class VERSION {static int SDK_INT=33;}}
  static final class Manifest {static final class permission {static final String POST_NOTIFICATIONS="notifications";}}
  static final class PackageManager {static final int PERMISSION_GRANTED=0;}
  static final class OnDeviceAdbWirelessManager {static boolean paired;static boolean hasPaired(Object c){return paired;}}
  static final class OPERATIONS {static boolean busy;static boolean running(){return busy;}}
  record Action(String label,boolean primary,Runnable click){}
  static final class Entry {
    static final int TEXT=1,MUTED=2;
    Object backend;boolean wireless=true;String unavailable="";int permission=0;
    int requests,launches,reconnects,computer,navigated=-1;
    final List<Action> actions=new ArrayList<>();final List<String> rows=new ArrayList<>();
    String s(int value){return LABELS[value];}Object text(String s){return s;}
    Object text(String s,int size,int color,boolean bold){return s;}
    Object margin(int n){return n;}LinearLayout box(){return new LinearLayout();}
    void add(Object v,int n){}boolean wirelessOnboardingAvailable(){return wireless;}
    String connectionSummary(){return "state";}String managementUnavailable(){return unavailable;}
    Object action(String label,boolean primary,Runnable click){actions.add(new Action(label,primary,click));return label;}
    LinearLayout navigationGroup(int n){return new LinearLayout();}
    void navigationItem(LinearLayout group,String label,String summary,String icon,Runnable click){rows.add(label);}
    void navigate(int n){navigated=n;}void primaryConnect(){reconnects++;}
    void beginDirectBootstrap(){computer++;}void showConnectionHelp(){}void showOtherConnections(){}
    int checkSelfPermission(String p){return permission;}
    void requestPermissions(String[] p,int request){check(p.length==1&&request==7312,"Request only notification permission");requests++;}
    void launchUserApprovedPairingFlow(){launches++;}
ACTUAL_METHODS
  }
  static Action primary(Entry e,String name){
    List<Action> candidates=e.actions.stream().filter(Action::primary).toList();
    check(candidates.size()==1,"Exactly one primary connection action");
    check(candidates.get(0).label().equals(name),"Action follows actual build and pairing state");
    check(e.launches==0&&e.computer==0&&e.reconnects==0,"Rendering cannot start authorization");
    return candidates.get(0);
  }
  public static void main(String[] args){
    Entry e=new Entry();OnDeviceAdbWirelessManager.paired=false;e.connectionAccessScreen();
    primary(e,"nav_start_setup").click().run();check(e.launches==1,"Fresh setup is explicitly started");
    e=new Entry();OnDeviceAdbWirelessManager.paired=true;e.connectionAccessScreen();
    primary(e,"wireless_adb_live_connect").click().run();check(e.reconnects==1&&e.launches==0,"Paired user reconnects without re-pairing");
    e=new Entry();e.wireless=false;e.connectionAccessScreen();
    primary(e,"ui_connect_using_computer").click().run();check(e.computer==1,"No-JNI build keeps working computer route");
    check(!e.rows.contains("nav_other_connections"),"Computer route is not hidden behind alternatives in a no-JNI build");
    e=new Entry();e.backend=new Object();e.connectionAccessScreen();
    primary(e,"nav_view_esims").click().run();check(e.navigated==0&&e.launches==0,"Connected page returns to cards");
    e=new Entry();e.unavailable="unsupported";e.connectionAccessScreen();
    check(e.actions.isEmpty()&&e.launches==0,"Unsupported device never offers a false authorization action");
    e=new Entry();e.permission=1;e.startWirelessPairing();
    check(e.requests==1&&e.launches==0,"Notification denial cannot launch pairing");
    e.permission=0;e.startWirelessPairing();check(e.launches==1,"Explicit retry after permission grant starts once");
    e=new Entry();OPERATIONS.busy=true;e.startWirelessPairing();
    check(e.launches==0&&e.requests==0,"Active card operation blocks new pairing");OPERATIONS.busy=false;
    e=new Entry();e.wireless=false;e.startWirelessPairing();
    check(e.launches==0&&e.requests==0,"Missing wireless capability blocks setup");
    System.out.println("PASS "+checks+" actual settings/authorization method checks (display adapters, no live pairing)");
  }
}
'''.replace('RESOURCES', resources).replace('LABELS_VALUE', labels).replace('ACTUAL_METHODS', page)
# Java's fully qualified platform permission is represented by the display adapter.
source = source.replace('android.Manifest.permission.', 'Manifest.permission.')
source = source.replace('android.content.pm.PackageManager.', 'PackageManager.')
java = Path(os.environ.get('JAVA_HOME', '/usr')) / 'bin'
with tempfile.TemporaryDirectory() as tmp:
    fixture = Path(tmp) / 'SettingsPageTest.java'
    fixture.write_text(source)
    subprocess.run([str(java / 'javac'), '-d', tmp, str(fixture)], check=True)
    subprocess.run([str(java / 'java'), '-cp', tmp, 'SettingsPageTest'], check=True)
