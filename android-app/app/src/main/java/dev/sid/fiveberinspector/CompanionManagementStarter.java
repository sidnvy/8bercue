package dev.sid.fiveberinspector;
import android.os.*;
import java.lang.reflect.Method;
/** Independent typed management Binder. No arbitrary RPC, APDU or command input. */
public final class CompanionManagementStarter {
 private static Bundle call(Object provider,String method,Bundle extras)throws Exception {
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
 public static void main(String[]args)throws Exception {
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
  lifetime.linkToDeath(()->{
    // A foreground app disappearing must not interrupt an in-flight card write.
    // Exit only after exclusion is released. Quarantined workers retain the process
    // and durable block for explicit recovery; they are never silently abandoned.
    Thread shutdown=new Thread(()->{
      while(CardTransactionLock.isActive()) {
        try {Thread.sleep(250);}catch(InterruptedException ignored){return;}
      }
      System.exit(0);
    },"8bercue-client-lifetime");shutdown.setDaemon(true);shutdown.start();
  },0);
  Bundle payload=new Bundle();payload.putString("nonce",nonce);payload.putInt("protocol",CompanionBinderProvider.MANAGEMENT_VERSION);payload.putInt("versionCode",BuildConfig.VERSION_CODE);
  payload.putString("apkSha256",NativeLpacBundle.digest(new java.io.File(NativeLpacBundle.installedApk())));
  payload.putBinder("managementBinder",new ProfileUserService("Independent Binder").asBinder());
  Bundle accepted=call(provider,CompanionBinderProvider.MANAGEMENT_METHOD,payload);
  if(accepted==null||!accepted.getBoolean("accepted"))throw new SecurityException("Management handoff rejected");
  }
  System.out.println("MANAGEMENT_BINDER_READY");System.out.flush();Looper.loop();
 }
 private CompanionManagementStarter(){}
}
