#!/usr/bin/env python3
"""Run production refresh/status methods with controlled Binder and display I/O.

Checks visible read-only inventory and contextual review eligibility, not Android
rendering, real Binder transport or a live card.
"""
import os
from pathlib import Path
import re
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
ui = (root / 'app/src/main/java/dev/sid/fiveberinspector/MainActivity.java').read_text()
methods = ui[ui.index('  private void refresh()'):ui.index('  private void checkRecovery()')]
methods += ui[ui.index('  private void checkRecovery()'):ui.index('  private void reconcile()')]
start = ui.index('  private void updateStatusRegion()')
methods += ui[start:ui.index('  private void status(String a, String b)', start)]
policy = (root / 'app/src/main/java/dev/sid/fiveberinspector/RecoveryUiPolicy.java').read_text()
policy = policy[policy.index('final class RecoveryUiPolicy'):].replace('final class RecoveryUiPolicy', 'static final class RecoveryUiPolicy', 1)
names = sorted(set(re.findall(r'R\.string\.(\w+)', methods)))
resources = '\n'.join(f'static final int {name}={i};' for i, name in enumerate(names))
labels = ','.join('"' + name + '"' for name in names)
source = r'''
import java.util.*;
public class InventoryUiTest {
 static int checks;static void check(boolean ok,String m){checks++;if(!ok)throw new AssertionError(m);}
 static final class R{static final class string{RESOURCES}}
 static final String[] LABELS={LABELS_VALUE};
 static class JSONObject{
  static final Map<String,JSONObject> wire=new HashMap<>();final Map<String,Object> values=new HashMap<>();
  JSONObject(){}JSONObject(String s){values.putAll(wire.get(s).values);}
  JSONObject put(String k,Object v){values.put(k,v);return this;}boolean has(String k){return values.containsKey(k);}
  int length(){return values.size();}boolean optBoolean(String k,boolean d){return values.get(k) instanceof Boolean?(Boolean)values.get(k):d;}
  String optString(String k){return optString(k,"");}String optString(String k,String d){return values.get(k) instanceof String?(String)values.get(k):d;}
  JSONObject optJSONObject(String k){return values.get(k) instanceof JSONObject?(JSONObject)values.get(k):null;}
  JSONArray optJSONArray(String k){return values.get(k) instanceof JSONArray?(JSONArray)values.get(k):null;}
  public String toString(){String k="r"+wire.size();wire.put(k,this);return k;}
 }
 static class JSONArray{final List<JSONObject> rows=new ArrayList<>();JSONArray put(JSONObject r){rows.add(r);return this;}int length(){return rows.size();}JSONObject optJSONObject(int i){return rows.get(i);}}
 interface IProfileService{String listProfiles();default String getOperationStatus(){throw new AssertionError("Status not provided");}}
 interface Work{JSONObject get()throws Exception;}interface Result{void accept(JSONObject r)throws Exception;}
 static class ShellCompanionClient{static JSONObject list(){throw new AssertionError("Unexpected legacy fallback");}}
 static class CompatibilityDiagnostics{enum Stage{INVENTORY}enum Failure{INVENTORY_READ_FAILED}}
ACTUAL_POLICY
 static class NotificationPresentation{static String message(String status){return status;}}
 static class SecureElementFailure{static Object message(String s){return s;}}
 static class UiMessages{static String known(Object context,Object error){return "";}}
 static class OPERATIONS{static boolean running(){return false;}static String title(){return "Reading";}}
 static class UiStatusPolicy{static boolean visible(boolean a,boolean b,boolean c,boolean d,String e){return a||b||c||d;}}
 static class View{static final int VISIBLE=0,GONE=8,ACCESSIBILITY_LIVE_REGION_POLITE=1;boolean enabled;void setEnabled(boolean b){enabled=b;}void setAlpha(float a){}boolean isEnabled(){return enabled;}}
 static class Widget{String text="";int visibility;void setText(String s){text=s;}void setVisibility(int n){visibility=n;}void setAccessibilityLiveRegion(int n){}}
 static class Prefs{boolean pending;boolean getBoolean(String k,boolean d){return pending;}Prefs edit(){return this;}Prefs putBoolean(String k,boolean v){pending=v;return this;}Prefs remove(String k){pending=false;return this;}boolean commit(){return true;}}
 static class DetailSelection{void open(Object a,Object b,Object c,int d){}}
 IProfileService backend;boolean binderReadOnlyInventory,fallback,notificationCleanupAvailable,recoveryReviewEligible,recoveryReconnectNeeded,restoreDetailAfterRead;
 JSONObject inventory=new JSONObject(),compatibility=new JSONObject(),detailProfile;int screen,selectedProfile=-1,renders,cleared,failures;String selectedProfileKey="",title="",detail="",notice="",notificationStatus="";
 final List<View> sheetControls=new ArrayList<>(),taskControls=new ArrayList<>();final Map<View,Boolean> controlAvailability=new HashMap<>();Widget busyProgress;
 final Prefs prefs=new Prefs();final DetailSelection detailSelection=new DetailSelection();final Widget statusBox=new Widget(),statusTitle=new Widget(),statusDetail=new Widget();
 String s(int id){return LABELS[id];}Prefs getPreferences(int n){return prefs;}void connect(){throw new AssertionError("Unexpected connection");}
 void run(String label,boolean change,Work work,Result result){try{result.accept(work.get());}catch(Exception e){throw new RuntimeException(e);}}
 void compatibilityFailure(Object a,Object b){failures++;}void clearDetailSelection(){cleared++;}String recoveryFailure(String s){return "Wait for service";}
 void status(String a,String b){notice="";title=a;detail=b;updateStatusRegion();}void statusPreservingOutcome(String a,String b){title=a;detail=b;updateStatusRegion();}
 String connectionNotice(JSONObject r){return "Connected";}void render(){renders++;updateStatusRegion();}String canonicalTitle(){return title;}
ACTUAL_METHODS
 static JSONObject inventory(String state,boolean review){
  JSONObject result=new JSONObject().put("ok",true).put("profiles",new JSONArray().put(new JSONObject().put("selectionId","opaque")));
  if(!state.isEmpty())result.put("recoveryState",state).put("recoveryReviewEligible",review);
  return result;
 }
 static JSONObject ambiguous(){return new JSONObject().put("ok",false).put("capabilityError","AMBIGUOUS_EUICC");}
 static InventoryUiTest read(JSONObject response){InventoryUiTest e=new InventoryUiTest();e.backend=()->response.toString();e.refresh();return e;}
 public static void main(String[] args){
  for(String state:List.of("READ_ONLY_RECOVERED","READ_ONLY_CURRENT_CARD")){
   InventoryUiTest e=read(inventory(state,false));
   check(e.inventory.has("profiles")&&e.renders==1,"Read-only profiles reach the rendered screen: "+state);
   check(e.prefs.pending&&!e.recoveryReconnectNeeded,"Observation keeps changes paused without demanding reconnect");
   check(e.statusTitle.text.equals("recovery_view_only_title")&&e.statusDetail.text.equals("recovery_view_only_summary"),"Readable card has a concise view-only explanation");
   check(!e.recoveryReviewEligible,"Other-card/unbound observation has no impossible review action");
  }
  InventoryUiTest e=read(inventory("READ_ONLY_RECOVERED",true));check(e.recoveryReviewEligible,"Eligible original-card read exposes contextual confirmation");
  e=read(inventory("READ_ONLY_CURRENT_CARD",false).put("recoveryScope","other_card"));
  check(e.statusDetail.text.equals("recovery_other_card_summary"),"Cross-card pause explains which previous card needs review");
  e.recoveryReviewEligible=true;JSONObject legacy=inventory("",false);e.backend=()->legacy.toString();e.refresh();check(!e.recoveryReviewEligible,"Missing eligibility cannot inherit a stale review action");
  e=read(inventory("",false));check(e.inventory.has("profiles")&&!e.prefs.pending,"Healthy reads keep the ordinary inventory flow");
  e.recoveryReviewEligible=true;JSONObject failure=new JSONObject().put("ok",false).put("recoveryError","RECOVERY_WORKER_ACTIVE");e.backend=()->failure.toString();e.refresh();
  check(!e.inventory.has("profiles")&&e.cleared==1&&!e.recoveryReviewEligible,"Failed read clears stale profiles, selection and review action");
  check(e.prefs.pending&&e.recoveryReconnectNeeded,"Unconfirmed worker still pauses access");
  check(e.statusTitle.text.equals("recovery_access_paused"),"A blocked read does not claim the card is viewable");
  for(boolean pending:List.of(false,true)){
   e=read(inventory("",false));e.prefs.pending=pending;e.notice="Previous status";e.notificationCleanupAvailable=true;e.recoveryReviewEligible=true;
   e.backend=()->ambiguous().toString();e.refresh();
   check(e.statusTitle.text.equals("ui_choose_a_card"),"Multiple readable cards request a choice rather than report an unavailable card");
   check(e.statusDetail.text.equals("ui_more_than_one_euicc_is_readable_select_the_card_you_want_to_mana"),"Choice prompt does not append diagnostic instructions");
   check(!e.inventory.has("profiles")&&!e.recoveryReviewEligible&&!e.notificationCleanupAvailable&&e.cleared==1,"Choice clears stale inventory and current-card actions");
   check(e.prefs.pending==pending,"Choice preserves any unresolved operation restriction");
   check(e.failures==0,"Normal multi-card choice is not recorded as a connection failure");
  }
  e=read(ambiguous().put("recoveryError","RECOVERY_WORKER_ACTIVE"));
  check(e.statusTitle.text.equals("recovery_access_paused")&&e.recoveryReconnectNeeded,"An actual safety failure takes priority over a choice hint");
  e=read(new JSONObject().put("ok",false).put("capabilityError","CARD_NOT_PRESENT"));
  check(e.statusTitle.text.equals("ui_card_unavailable")&&e.failures==1,"Actual card failures retain their error flow");
  e=read(inventory("READ_ONLY_RECOVERED",true));final InventoryUiTest selected=e;
  for(boolean oldCard:List.of(false,true,false,true)){
   JSONObject shown=inventory(oldCard?"READ_ONLY_RECOVERED":"",oldCard);
   JSONObject state=new JSONObject().put("ok",true).put("status",oldCard?"unknown":"idle").put("requiresReconciliation",oldCard).put("reviewRequired",oldCard).put("notificationStatus","none");
   selected.backend=new IProfileService(){public String listProfiles(){return shown.toString();}public String getOperationStatus(){return state.toString();}};
   selected.checkRecovery();
   check(selected.prefs.pending==oldCard,"A previous card's local pending preference cannot block the next card");
   check(selected.recoveryReviewEligible==oldCard,"Review action follows the selected card, never the previous one");
   check(selected.statusTitle.text.equals("recovery_view_only_title")==oldCard,"View-only warning clears when a healthy card is selected");
  }
  System.out.println("PASS "+checks+" actual inventory/status UI checks (controlled Binder/display I/O)");
 }
}
'''.replace('RESOURCES',resources).replace('LABELS_VALUE',labels).replace('ACTUAL_METHODS',methods).replace('ACTUAL_POLICY',policy).replace('MainActivity.this','this')
with tempfile.TemporaryDirectory() as tmp:
    fixture = Path(tmp) / 'InventoryUiTest.java'
    fixture.write_text(source)
    java = Path(os.environ.get('JAVA_HOME', '/usr')) / 'bin'
    subprocess.run([str(java / 'javac'), str(fixture)], check=True)
    subprocess.run([str(java / 'java'), '-cp', tmp, 'InventoryUiTest'], check=True)
