package dev.sid.fiveberinspector;
public final class UiFlowOfflineTest {
 static int checks;
 static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 public static void main(String[] args)throws Exception{
  String path="/data/app/~~Abc+=/dev.sid.fiveberinspector-X_y9==/base.apk";
  String command=ConnectionCommand.forApk(path);
  check(command.startsWith("adb shell 'p=$(pm path dev.sid.fiveberinspector"),"Resolve installed APK on Android, not host");
  check(command.contains("CLASSPATH=${p#package:} app_process / dev.sid.fiveberinspector.Connect"),"Use the reporting foreground entrypoint");
  check(!command.contains("/dev/null")&&!command.endsWith("&'"),"Do not silently background the connection status client");
  check(!command.contains("python")&&!command.contains("curl")&&!command.contains("wget"),"No external download or interpreter");
  check(command.equals(ConnectionCommand.forApk(path.replace("Abc", "NewInstallation"))),"Stable across installation paths and phones");
  check(!command.contains(path),"Do not expose installation-specific directory in copied command");
  check(ConnectionFeedback.countdown(120000).contains("2:00"),"Visible initial two-minute window");
  check(ConnectionFeedback.countdown(1001).contains("0:02"),"Countdown rounds up");
  check(ConnectionFeedback.countdown(-1).equals("Connection window expired"),"Expired window never shows negative time");
  for(String code:new String[]{"STARTING","WINDOW_CLOSED","APP_CHANGED","WINDOW_CHANGED","APP_CLOSED","START_FAILED","TIMEOUT"}){
    check(ConnectionFeedback.allowed(code),"Only documented bootstrap states");
    check(!ConnectionFeedback.message(code).isEmpty(),"Every connection state has recovery guidance");
  }
  for(String bad:new String[]{null,"","CONNECTED","profile ICCID secret","MANAGEMENT_BINDER_READY"})check(!ConnectionFeedback.allowed(bad),"Reject arbitrary/success feedback: only a verified Binder establishes connection");
  check(ConnectionFeedback.code(new java.lang.reflect.InvocationTargetException(new SecurityException("Start connection from the app first"))).equals("WINDOW_CLOSED"),"Unwrap system Binder errors");
  check(ConnectionFeedback.code(new RuntimeException("private activation-code or ICCID")).equals("START_FAILED"),"Unknown exception cannot leak identifiers");
  check(ConnectionFeedback.message("WINDOW_CLOSED").contains("Connect using computer"),"Closed window tells user where to authorize");
  check(ConnectionFeedback.message("TIMEOUT").contains("late connection"),"Timeout is unconfirmed, not a false failure guarantee");
  for(String invalid:new String[]{"/sdcard/app.apk",path+";id",path+"\n",path.replace("X_y9","X'Y"),path.replace("X_y9","X$(id)"),"/data/app/other-app/base.apk",null}){boolean rejected=false;try{ConnectionCommand.forApk(invalid);}catch(IllegalArgumentException e){rejected=true;}check(rejected,"Reject untrusted path");}
  check(RecoveryUiPolicy.needsCardChoice("AMBIGUOUS_EUICC"),"Unselected multi-card is a selection step, not a lost write");
  check(!RecoveryUiPolicy.needsCardChoice("CHANNEL_CLEANUP_FAILED"),"Unconfirmed cleanup still blocks recovery");
  check(RecoveryUiPolicy.blocks(true,true,false,"verified"),"A past unknown record still blocks even after current success");
  check(RecoveryUiPolicy.keepsPending(false,true),"Unresolved notification must retain pending even after a clean operation journal");
  check(RecoveryUiPolicy.keepsPending(true,false),"Unknown operation must retain pending");
  check(!RecoveryUiPolicy.keepsPending(false,false),"Only complete operation and notification review can dismiss pending");
  check(RecoveryUiPolicy.blocks(true,false,false,"unknown"),"A read-only snapshot cannot unlock an unknown operation");
  check(!RecoveryUiPolicy.blocks(true,false,false,"reviewed_observed"),"Audited lifecycle review can clear the UI block");
  check(RecoveryUiPolicy.blocks(true,true,false,"reviewed_observed"),"Historical uncertainty still blocks after current review");
  check(UiStatusPolicy.visible(false,false,false,true,"Card unavailable"),"Connected Binder must not hide card-access failure");
  check(UiStatusPolicy.visible(false,false,false,true,"Choose a card"),"Card-selection guidance stays visible");
  check(UiStatusPolicy.visible(true,false,false,true,"Card connected"),"Worker progress stays visible");
  check(UiStatusPolicy.visible(false,true,false,true,"Card connected"),"Pending recovery stays visible");
  check(UiStatusPolicy.visible(false,false,true,true,"Card connected"),"Uncertain result stays visible");
  check(!UiStatusPolicy.visible(false,false,false,true,"Card connected"),"Normal verified homepage avoids extra status box");
  String session="a".repeat(48),card="b".repeat(64),first="8901000000000001234",alias="8901999999999991234";
  check(first.substring(0,4).equals(alias.substring(0,4))&&first.substring(first.length()-4).equals(alias.substring(alias.length()-4)),"Fixture has identical masked display");
  String key=ProfileUiIdentity.key(session,card,first);
  check(!key.equals(ProfileUiIdentity.key(session,card,alias)),"Same masked ICCID cannot alias a different full profile");
  check(!key.equals(ProfileUiIdentity.key(session,"c".repeat(64),first)),"Same ICCID on another card cannot alias");
  check(!key.equals(ProfileUiIdentity.key("d".repeat(48),card,first)),"New connection invalidates old UI identity");
  check(key.equals(ProfileUiIdentity.key(session,card,first)),"Refresh keeps same complete selection in one connection");
  boolean incomplete=false;try{ProfileUiIdentity.key(session,card,"8901...1234");}catch(SecurityException expected){incomplete=true;}check(incomplete,"Masked identifier cannot create a selection identity");
  ProfileDetailSession selected=new ProfileDetailSession();Object inventory=new Object(),backend=new Object(),profile=new Object();selected.open(inventory,backend,profile,2);
  check(selected.matches(inventory,backend,profile,2),"Current explicit profile selection");
  check(!selected.matches(new Object(),backend,profile,2),"Replaced inventory invalidates selection");
  check(!selected.matches(inventory,new Object(),profile,2),"Replaced service invalidates selection");
  check(!selected.matches(inventory,backend,new Object(),2),"Different profile invalidates selection");
  check(!selected.matches(inventory,backend,profile,1),"Index alone never identifies selected profile");
  selected.clear();check(!selected.matches(inventory,backend,profile,2),"Background/navigation clears permission to submit");
  for(String op:new String[]{"switch","disable","rename"})check(ProfileActionPolicy.executesDirectly(op),"Explicit reversible click authorizes single prepare/execute");
  for(String op:new String[]{"delete","download","cleanup",null,"arbitrary"})check(!ProfileActionPolicy.executesDirectly(op),"Irreversible/unknown action never skips confirmation");
  System.out.println("PASS "+checks+" UI/command regression assertions");
  if(args.length==1)System.out.println(command);
 }
}