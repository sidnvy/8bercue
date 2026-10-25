#!/usr/bin/env python3
"""Execute extracted production completion/pairing methods with controlled I/O.
No real Android Binder, Keystore, cryptography or new phone pairing is claimed.
"""
import os,pathlib,subprocess,tempfile
root=pathlib.Path(__file__).resolve().parents[1]
ui=(root/'app/src/main/java/dev/sid/fiveberinspector/MainActivity.java').read_text()
manager=(root/'app/src/release/java/dev/sid/fiveberinspector/OnDeviceAdbWirelessManager.java').read_text()
primary=ui[ui.index('  private void primaryConnect()'):ui.index('  private void home()')]
pair=manager[manager.index('    static void pair('):manager.index('    static boolean hasPaired(')]
source=r"""
import java.util.*;import java.io.*;import java.security.*;import java.util.function.*;
public class WirelessCompletionTest {
 static int checks;
 static void ok(boolean value){if(!value)throw new AssertionError();checks++;}
 public static void main(String[] args)throws Exception {
  Entry e=new Entry();LocalAdbBootstrap.ready=true;e.attach=true;e.primaryConnect();ok(e.backend!=null&&e.title.equals("connected"));
  e=new Entry();e.primaryConnect();ok(e.backend==null&&e.title.equals("3")&&e.detail.equals("4"));
  LocalAdbBootstrap.ready=false;e=new Entry();e.primaryConnect();ok(e.title.equals("2"));
  OnDeviceAdbWirelessManager.paired=false;e=new Entry();e.primaryConnect();ok(e.preview);
  OnDeviceAdbWirelessManager.paired=true;e=new Entry();e.wireless=false;e.primaryConnect();ok(e.direct);
  Context c=new Context();c.prefs.values.put("serverFingerprint","legacy-fixture");c.prefs.values.put("pairedGuid","previous-fixture");
  PairingEntry.pair(c,new OnDeviceAdbDiscovery.Endpoint(),new char[0]);
  ok(c.prefs.values.get("serverFingerprint").equals("legacy-fixture")&&c.prefs.values.get("pairedGuid").equals("paired-fixture"));
  OnDeviceAdbPairingTransport.fail=true;
  try{PairingEntry.pair(c,new OnDeviceAdbDiscovery.Endpoint(),new char[0]);throw new AssertionError();}catch(IOException expected){ok(c.prefs.values.get("serverFingerprint").equals("legacy-fixture"));}
  OnDeviceAdbPairingTransport.fail=false;OnDeviceAdbPairingTransport.guid="";
  try{PairingEntry.pair(c,new OnDeviceAdbDiscovery.Endpoint(),new char[0]);throw new AssertionError();}catch(IOException expected){ok(c.prefs.values.get("pairedGuid").equals("paired-fixture"));}
  OnDeviceAdbPairingTransport.guid="paired-fixture";c.prefs.commit=false;
  try{PairingEntry.pair(c,new OnDeviceAdbDiscovery.Endpoint(),new char[0]);throw new AssertionError();}catch(IOException expected){ok(c.prefs.values.get("serverFingerprint").equals("legacy-fixture"));}
  System.out.println("PASS "+checks+" actual completion/pairing method checks (controlled I/O, no live pairing)");
 }
 static final class R {static final class string {static final int wireless_adb_live_reconnecting=1,wireless_adb_live_unavailable=2,ui_connection_not_completed=3,wireless_adb_attach_pending=4;}}
 static final class JSONObject {
  final Map<String,Object> values=new HashMap<>();JSONObject put(String k,Object v){values.put(k,v);return this;}
  boolean optBoolean(String k){return Boolean.TRUE.equals(values.get(k));}String optString(String k,String fallback){return (String)values.getOrDefault(k,fallback);}
 }
 interface Job {JSONObject run()throws Exception;}
 static final class Entry {
  boolean wireless=true,attach,preview,direct;Object backend;String title="",detail="";
  String s(int value){return Integer.toString(value);}boolean wirelessOnboardingAvailable(){return wireless;}
  void beginDirectBootstrap(){direct=true;}void showWirelessDiscoveryPreview(){preview=true;}
  void connectDirect(){if(attach){backend=new Object();title="connected";}}
  void status(String t,String d){title=t;detail=d;}
  void run(String label,boolean mutate,Job job,Consumer<JSONObject> result){try{JSONObject r=job.run();title="completed";result.accept(r);}catch(Exception x){throw new RuntimeException(x);}}
PRIMARY
 }
 static final class LocalAdbBootstrap {
  static boolean ready;static final class Result {boolean ready;String description="controlled failure";}
  static Result start(Object c){Result r=new Result();r.ready=ready;return r;}
 }
 static final class OnDeviceAdbWirelessManager {static boolean paired=true;static boolean hasPaired(Object c){return paired;}}
 static final class OnDeviceAdbDiscovery {static final class Endpoint {}}
 static final class OnDeviceAdbIdentity {static OnDeviceAdbIdentity getOrCreate(Context c)throws Exception{return new OnDeviceAdbIdentity();}}
 static final class OnDeviceAdbPairingTransport {
  static boolean fail;static String guid="paired-fixture";
  static String pair(OnDeviceAdbDiscovery.Endpoint e,char[] code,OnDeviceAdbIdentity id)throws IOException,GeneralSecurityException {if(fail)throw new IOException();return guid;}
 }
 static final class Context {static final int MODE_PRIVATE=0;final Prefs prefs=new Prefs();Prefs getSharedPreferences(String name,int mode){return prefs;}}
 static final class Prefs {final Map<String,String> values=new HashMap<>();boolean commit=true;Editor edit(){return new Editor(this);}}
 static final class Editor {final Prefs prefs;final Map<String,String> changes;Editor(Prefs p){prefs=p;changes=new HashMap<>(p.values);}Editor remove(String k){changes.remove(k);return this;}Editor putString(String k,String v){changes.put(k,v);return this;}boolean commit(){if(prefs.commit){prefs.values.clear();prefs.values.putAll(changes);}return prefs.commit;}}
 static final class PairingEntry {
PAIR
 }
}
""".replace('PRIMARY',primary).replace('PAIR\n',pair+'\n')
java=pathlib.Path(os.environ.get('JAVA_HOME','/usr'))/'bin'
with tempfile.TemporaryDirectory() as tmp:
    path=pathlib.Path(tmp)/'WirelessCompletionTest.java';path.write_text(source)
    subprocess.run([str(java/'javac'),'-d',tmp,str(path)],check=True)
    subprocess.run([str(java/'java'),'-cp',tmp,'WirelessCompletionTest'],check=True)
