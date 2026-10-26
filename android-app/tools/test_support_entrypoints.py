#!/usr/bin/env python3
"""Execute actual support/advanced/records menus with display-only adapters."""
import os,re,subprocess,tempfile
from pathlib import Path
r=Path(__file__).resolve().parents[1]
s=(r/'app/src/main/java/dev/sid/fiveberinspector/MainActivity.java').read_text()
def method(start,end):return s[s.index(start):s.index(end)]
methods='\n'.join([method('  private void supportScreen()', '  private void chooseDiagnosticExport()'),method('  private void advancedScreen()', '  private void cardInformationScreen()'),method('  private void recordsScreen()', '  private void connectionScreen()')])
# Restrict each fixture to the production menu method itself.
names=sorted(set(re.findall(r'R\.string\.(\w+)',methods)))
constants='\n'.join('static final int '+n+'='+str(i)+';' for i,n in enumerate(names))
labels=','.join('"'+n+'"' for n in names)
fixture=r'''import java.util.*;
public class SupportEntryTest {
 static int checks;static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 static class R{static class string{CONSTANTS}}
 static final String[] LABELS={LABELS_VALUE};
 static class BuildConfig{static boolean DEBUG;}
 static class LinearLayout{}
 static class SettingsNavigation{static int CARD=12,RECOVERY=14,LAB=13,SUPPORT=9;}
 static class CompatibilityDiagnostics{static Object failure;static Object lastFailure(){return failure;}}
 static class Data{boolean pending;int count;int length(){return count;}boolean getBoolean(String n,boolean fallback){return pending;}}
 final Data compatibility=new Data(),prefs=new Data();Object backend;boolean recoveryReviewEligible,notificationCleanupAvailable;String notificationStatus="";List<String> recentResults=new ArrayList<>(),items=new ArrayList<>();static int MUTED=1,TEXT=2;
 String s(int n){return LABELS[n];}Object text(String v){return v;}Object text(String v,int size,int color,boolean bold){return v;}
 Object text(String v,int size){return v;}void add(Object v,int n){}Data getPreferences(int n){return prefs;}
 LinearLayout navigationGroup(int n){return new LinearLayout();}
 void navigationItem(LinearLayout g,String label,String summary,String icon,Runnable action){items.add(label);}
 Object action(String label,boolean primary,Runnable click){items.add(label);return label;}
 static class UiMessages{static String known(Object context,Object message){return "Notification";}}
 static class NotificationPresentation{static Object message(String status){return status;}}
 void prepareNotificationCleanup(){}
 void navigate(int n){}void showConnectionHelp(){}void showCompatibility(){}void reportProblem(){}void chooseDiagnosticExport(){}void offerCompatibilityReport(){}void reviewUnknownLifecycle(){}
ACTUAL
 public static void main(String[] args){
  SupportEntryTest e=new SupportEntryTest();BuildConfig.DEBUG=false;e.advancedScreen();
  check(e.items.equals(List.of("nav_card_information")),"Release Advanced contains no compatibility/recovery/Lab workflow");
  e=new SupportEntryTest();BuildConfig.DEBUG=true;e.advancedScreen();
  check(e.items.contains("ui_compatibility_check")&&e.items.contains("nav_verify_state")&&e.items.contains("nav_lab_tools"),"Developer tools remain in debug builds");
  BuildConfig.DEBUG=false;e=new SupportEntryTest();e.supportScreen();
  check(!e.items.contains("nav_connection_details"),"No empty diagnostic entry for a new user");
  e=new SupportEntryTest();e.backend=new Object();e.supportScreen();
  check(e.items.contains("nav_connection_details"),"Connection details available under Help when useful");
  e=new SupportEntryTest();e.compatibility.count=1;e.supportScreen();check(e.items.contains("nav_connection_details"),"Failed connection observations remain accessible for support");
  e=new SupportEntryTest();e.recordsScreen();check(!e.items.contains("ui_verify_card_and_recovery")&&!e.items.contains("recovery_review_button"),"Healthy records have no generic verification button");
  e=new SupportEntryTest();e.prefs.pending=true;e.recordsScreen();check(!e.items.contains("recovery_review_button"),"An ineligible old operation cannot offer impossible review");
  e=new SupportEntryTest();e.prefs.pending=true;e.recoveryReviewEligible=true;e.recordsScreen();check(e.items.contains("recovery_review_button"),"Eligible operation gets its contextual confirmation");
  e=new SupportEntryTest();e.notificationCleanupAvailable=true;e.recordsScreen();check(e.items.contains("ui_review_notification_cleanup"),"Required notification recovery remains available in context");
  System.out.println("PASS "+checks+" actual support/advanced/record entrypoint checks (display adapters)");
 }
}'''.replace('CONSTANTS',constants).replace('LABELS_VALUE',labels).replace('ACTUAL',methods).replace('MainActivity.this','this')
# Guard the independent Home render region: it must not inject an extra generic recovery CTA.
region=s[s.index('    statusBox = box();'):s.index('    if (screen == 0) home();')]
assert 'this::reconcile' not in region and 'nav_verify_state' not in region
with tempfile.TemporaryDirectory() as tmp:
 p=Path(tmp)/'SupportEntryTest.java';p.write_text(fixture)
 java=Path(os.environ.get('JAVA_HOME','/usr'))/'bin'
 subprocess.run([str(java/'javac'),str(p)],check=True);subprocess.run([str(java/'java'),'-cp',tmp,'SupportEntryTest'],check=True)
