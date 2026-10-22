package dev.sid.fiveberinspector;
import android.os.*;
import java.lang.reflect.Method;
/** Independent typed management Binder. No arbitrary RPC, APDU or command input. */
public final class CompanionManagementStarter {
 static Bundle call(Object provider,String method,Bundle extras)throws Exception {
  Object attribution=Build.VERSION.SDK_INT>=31?attribution():null;
  for(Method m:provider.getClass().getMethods()){
   Class<?>[] t=m.getParameterTypes();
   if(!"call".equals(m.getName())||t.length!=5||t[1]!=String.class||t[2]!=String.class||t[3]!=String.class||t[4]!=Bundle.class)continue;
   Object caller=t[0]==String.class?"com.android.shell":attribution!=null&&t[0].getName().equals("android.content.AttributionSource")?attribution:null;
   if(caller!=null)return (Bundle)m.invoke(provider,caller,"dev.sid.fiveberinspector.companion",method,null,extras);
  }
  throw new UnsupportedOperationException("Provider interface unavailable");
 }
 @android.annotation.TargetApi(31) private static Object attribution(){return new android.content.AttributionSource.Builder(2000).setPackageName("com.android.shell").build();}
 public static void main(String[]args) {
  try { run(args); }
  catch (Exception failure) {
    System.err.println("8BERCUE_ERROR:" + ConnectionFeedback.code(failure));
    System.err.flush(); System.exit(1);
  }
 }
 private static void run(String[]args)throws Exception {
  if(android.os.Process.myUid()!=2000||args.length!=1||!"--connect-management".equals(args[0]))throw new SecurityException("Explicit Shell bootstrap required");
  Looper.prepareMainLooper();
  try(SystemProviderBridge.Connection connection=SystemProviderBridge.acquire("dev.sid.fiveberinspector.companion")) {
  Object provider=connection.provider;
  Bundle challenge=call(provider,CompanionBinderProvider.CHALLENGE_METHOD,new Bundle());
  if(challenge==null||challenge.getInt("protocol")!=CompanionBinderProvider.MANAGEMENT_VERSION||challenge.getInt("versionCode")!=BuildConfig.VERSION_CODE)
   throw new SecurityException("App version mismatch");
  String nonce=challenge.getString("nonce");if(!OperationTokens.isValid(nonce))throw new SecurityException("Invalid bootstrap challenge");
  IBinder lifetime=challenge.getBinder("clientLifetime");
  if(lifetime==null||!lifetime.pingBinder())throw new SecurityException("App lifetime unavailable");
  ProfileUserService service=new ProfileUserService("Independent Binder");
  Bundle payload=new Bundle();payload.putString("nonce",nonce);payload.putInt("protocol",CompanionBinderProvider.MANAGEMENT_VERSION);payload.putInt("versionCode",BuildConfig.VERSION_CODE);
  payload.putString("apkSha256",NativeLpacBundle.digest(new java.io.File(NativeLpacBundle.installedApk())));
  payload.putBinder("managementBinder",service.asBinder());
  Bundle accepted=call(provider,CompanionBinderProvider.MANAGEMENT_METHOD,payload);
  if(accepted==null||!accepted.getBoolean("accepted"))throw new SecurityException("Management handoff rejected");
  listen(service,NativeLpacBundle.digest(new java.io.File(NativeLpacBundle.installedApk())));
  }
  System.out.println("MANAGEMENT_BINDER_READY");System.out.flush();Looper.loop();
 }
  private static boolean appRunning(){
    try{
      java.lang.Process lookup=new ProcessBuilder("/system/bin/pidof","dev.sid.fiveberinspector").redirectErrorStream(true).start();
      boolean finished=lookup.waitFor(2,java.util.concurrent.TimeUnit.SECONDS);
      if(!finished){lookup.destroyForcibly();return false;}
      String line;try(java.io.BufferedReader reader=new java.io.BufferedReader(new java.io.InputStreamReader(lookup.getInputStream(),java.nio.charset.StandardCharsets.UTF_8))){line=reader.readLine();}
      return lookup.exitValue()==0&&line!=null&&!line.trim().isEmpty();
    }catch(Exception missing){return false;}
  }
  private static void listen(ProfileUserService service,String apkDigest){
    // Survives the app exiting and the USB cable being unplugged. A reboot still ends it.
    // Only contact the app after its process exists. Starting it from here is blocked on this phone.
    Thread waiter=new Thread(()->{
      int backoffMs=1000;
      while(true){
        try{Thread.sleep(backoffMs);}catch(InterruptedException stopped){return;}
        if(CardTransactionLock.isActive()||!appRunning())continue;
        try(SystemProviderBridge.Connection connection=SystemProviderBridge.acquire("dev.sid.fiveberinspector.companion")){
          Bundle state=call(connection.provider,CompanionBinderProvider.STATE_METHOD,new Bundle());
          if(state!=null&&state.getBoolean("connected")){backoffMs=1000;continue;}
          Bundle payload=new Bundle();
          payload.putString("apkSha256",apkDigest);
          payload.putBinder("managementBinder",service.asBinder());
          call(connection.provider,CompanionBinderProvider.REBIND_METHOD,payload);
          backoffMs=1000;
        }catch(Exception failure){
          backoffMs=Math.min(backoffMs*2,30000);
          Throwable cause=failure;
          while(cause instanceof java.lang.reflect.InvocationTargetException&&((java.lang.reflect.InvocationTargetException)cause).getTargetException()!=null)
            cause=((java.lang.reflect.InvocationTargetException)cause).getTargetException();
          if(cause instanceof SecurityException&&String.valueOf(cause.getMessage()).contains("Stale companion")){
            if(!CardTransactionLock.isActive())System.exit(0);
          }else System.err.println("8BERCUE_ERROR:REBIND_FAILED");
        }
      }
    },"8bercue-rebind");
    waiter.setDaemon(true);waiter.start();
  }
 private CompanionManagementStarter(){}
}
