package dev.sid.fiveberinspector;

import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/* Runs locally built ARM64 lpac 2.3 in a fixed read-only profile list mode.
 * Strictly fixed stdio HAL broker: no network and no caller-supplied APDUs.
 * Sensitive identifiers stay inside this Shell process.
 */
public final class PhoneNativeLpac {
  private static final String ROOT="/data/local/tmp/fiveber-lpac-lab";
  private PhoneNativeLpac(){}

  private static JSONObject req(String fn,String p)throws Exception{
    JSONObject payload=new JSONObject().put("func",fn);
    payload.put("param",p==null?JSONObject.NULL:p);
    return new JSONObject().put("type","apdu").put("payload",payload);
  }
  private static void cardPreflight(FiveberReaderSelector.Target expected)throws Exception{
    NativeLpacReadOnlyBridge b=new NativeLpacReadOnlyBridge(expected);
    try {
      int ec=b.handle(req("connect",null)).getJSONObject("payload").getInt("ecode");
      if(ec!=0)throw new IllegalStateException("HAL unavailable");
      int ch=b.handle(req("logic_channel_open","A0000005591010FFFFFFFF8900000100"))
        .getJSONObject("payload").getInt("ecode");
      if(ch<1||ch>3)throw new IllegalStateException("Unexpected channel");
      // Moto's 5ber returns GetEID in two frames (SW61 / GET RESPONSE).
      // Keep independent preflight verification but support bounded fragments.
      EidReadFrames assembled=new EidReadFrames(ch);
      String command=String.format(java.util.Locale.ROOT,
          "%02XE2910006BF3E035C015A",0x80|ch);
      for(int frame=0;frame<6;frame++) {
        JSONObject payload=b.handle(req("transmit",command)).getJSONObject("payload");
        if(payload.getInt("ecode")!=0)
          throw new SecurityException("GetEID read rejected");
        command=assembled.accept(payload.getString("data"));
        if(command==null)break;
      }
      byte[] eid=assembled.eid();
      byte[] hash=MessageDigest.getInstance("SHA-256").digest(eid);
      StringBuilder prefix=new StringBuilder();
      for(int i=0;i<32;i++)prefix.append(String.format(java.util.Locale.ROOT,"%02x",hash[i]&255));
      if(!expected.identityHash.equals(prefix.toString()))
        throw new SecurityException("Selected eUICC identity changed");
    } finally {b.close();}
  }
  private static String safeName(String name){
    if(name==null)return "";
    String s=name.replaceAll("[\\p{Cntrl}]","").replaceAll("\\b\\d{8,}\\b","[hidden]");
    return s.substring(0,Math.min(s.length(),70));
  }
  private static String mask(String s){
    return s==null||s.length()<8?"hidden":s.substring(0,4)+"..."+s.substring(s.length()-4);
  }
  private static JSONObject readWorker(AtomicReference<Process> child,FiveberReaderSelector.Target expected)throws Exception {
    Process proc=null;
    NativeLpacReadOnlyBridge bridge=null;
    try {
      ProcessBuilder pb=new ProcessBuilder(ROOT+"/lpac","profile","list");
      pb.directory(new File(ROOT));
      pb.environment().put("LPAC_APDU","stdio");
      pb.environment().put("LPAC_HTTP","stdio");
      pb.environment().put("LD_LIBRARY_PATH",ROOT);
      pb.environment().put("LPAC_APDU_DEBUG","false");
      pb.redirectError(ProcessBuilder.Redirect.to(new File("/dev/null")));
      proc=pb.start();child.set(proc);
      bridge=new NativeLpacReadOnlyBridge(expected);
      BufferedReader in=new BufferedReader(new InputStreamReader(proc.getInputStream(),StandardCharsets.UTF_8));
      BufferedWriter out=new BufferedWriter(new OutputStreamWriter(proc.getOutputStream(),StandardCharsets.UTF_8));
      JSONObject finalResult=null;
      for(int i=0;i<40;i++){
        String line=BoundedLines.read(in,100000);
        if(line==null)break;
        if(line.length()>100000)throw new SecurityException("IPC packet too large");
        JSONObject packet=new JSONObject(line);
        String kind=packet.optString("type");
        if("apdu".equals(kind)){
          JSONObject answer=bridge.handle(packet);
          out.write(answer.toString());out.newLine();out.flush();
        }else if("lpa".equals(kind)){
          // libeuicc prints the LPA result BEFORE euicc_fini()/disconnect.
          // Keep servicing the fixed APDU broker until the native process exits.
          if(finalResult!=null)throw new IllegalStateException("Multiple LPA results");
          finalResult=packet.getJSONObject("payload");
        }else throw new SecurityException("Unexpected lpac protocol operation");
      }
      if(finalResult==null||finalResult.optInt("code",-1)!=0)
        throw new IllegalStateException("Native lpac read failed");
      if(!proc.waitFor(5,TimeUnit.SECONDS)||proc.exitValue()!=0)
        throw new IllegalStateException("Native lpac did not terminate normally");
      JSONArray entries=finalResult.getJSONArray("data"), safe=new JSONArray();
      for(int i=0;i<entries.length();i++){
        if(i>=32)throw new IllegalStateException("Too many profiles");
        JSONObject p=entries.getJSONObject(i);
        safe.put(new JSONObject()
          .put("provider",safeName(p.optString("serviceProviderName")))
          .put("name",safeName(p.optString("profileName")))
          .put("nickname",p.isNull("profileNickname")?"":safeName(p.optString("profileNickname")))
          .put("state",safeName(p.optString("profileState")))
          .put("iccidMasked",mask(p.optString("iccid"))));
      }
      return new JSONObject().put("count",safe.length()).put("profiles",safe)
        .put("readOnly",true).put("engine","native libeuicc/lpac 2.3.0")
        .put("transport","phone HAL "+bridge.selectedReader());
    } finally {
      if(proc!=null) {
        try{proc.getOutputStream().close();}catch(Exception ignored){}
        if(proc.isAlive())proc.destroyForcibly();
        child.set(null);
      }
      if(bridge!=null)bridge.close();
    }
  }
  public static JSONObject listProfiles()throws Exception{
    if(android.os.Process.myUid()!=android.os.Process.SHELL_UID)throw new SecurityException("Requires Shell UID");
    // This remains the READ-ONLY path. Mutating transactions separately require
    // an enrolled device/card pair, confirmation, and independent readback.
    FiveberReaderSelector.Target expected=FiveberReaderSelector.discover();
    if(Build.VERSION.SDK_INT<36)
      throw new SecurityException("Unexpected Android SDK");
    try {cardPreflight(expected);}
    catch(Exception ex){throw new StageException("preflight",ex);}
    AtomicReference<Process> child=new AtomicReference<>();
    ExecutorService executor=Executors.newSingleThreadExecutor();
    Future<JSONObject> future=executor.submit(()->readWorker(child,expected));
    try{return future.get(25,TimeUnit.SECONDS);}
    catch(Exception ex){throw new StageException("native-worker",ex);}
    finally{
      if(!future.isDone()){
        future.cancel(true);
        Process p=child.get();
        if(p!=null)p.destroyForcibly();
      }
      executor.shutdownNow();
    }
  }
  /** Diagnostic phase only; exception messages never cross the App Binder. */
  public static final class StageException extends Exception {
    public final String phase;
    StageException(String phase,Throwable cause){super(phase,cause);this.phase=phase;}
  }

  public static void main(String[] args){
    if(args.length!=1||!"--read-only-profile-list".equals(args[0])){
      System.out.println("Only --read-only-profile-list is supported");return;
    }
    try{
      JSONObject safe=listProfiles();
      System.out.println("NATIVE_LPAC_PROFILE_LIST_SUCCESS");
      System.out.println(safe.toString());
    }catch(Throwable e){
      System.out.println("NATIVE_LPAC_PROFILE_LIST_FAILED "+e.getClass().getSimpleName());
    }
  }
}
