package dev.sid.fiveberinspector;
import java.io.ByteArrayOutputStream;
import java.util.Locale;
import java.util.Map;
import org.json.JSONObject;

/** Optional, EID-pinned capability reads. Caller holds CardTransactionLock throughout. */
public final class CardCapabilities {
 private static final String AID="A0000005591010FFFFFFFF8900000100";
 private CardCapabilities(){}
 private static byte[] query(boolean info,String expectedReader)throws Exception{
  NativeLpacReadOnlyBridge bridge=new NativeLpacReadOnlyBridge(info);
  try{
   if(bridge.handle(CardProfileInventory.req("connect",null)).getJSONObject("payload").getInt("ecode")!=0||!expectedReader.equals(bridge.selectedReader()))throw new SecurityException("Card reader changed");
   int ch=bridge.handle(CardProfileInventory.req("logic_channel_open",AID)).getJSONObject("payload").getInt("ecode");
   if(ch<1||ch>3)throw new SecurityException("Invalid capability channel");
   String command=String.format(Locale.ROOT,"%02XE29100",0x80|ch)+(info?"03BF2200":"06BF3E035C015A");
   ByteArrayOutputStream bytes=new ByteArrayOutputStream();
   for(int frame=0;frame<24;frame++){
    JSONObject reply=bridge.handle(CardProfileInventory.req("transmit",command)).getJSONObject("payload");
    if(reply.getInt("ecode")!=0)throw new SecurityException("Capability command refused");
    byte[] data=CardProfileInventory.fromHex(reply.getString("data"));
    if(data.length<2||data.length>8192||bytes.size()+data.length-2>16384)throw new SecurityException("Capability response too large");
    bytes.write(data,0,data.length-2);int sw1=data[data.length-2]&255,sw2=data[data.length-1]&255;
    if(sw1==0x90&&sw2==0)return bytes.toByteArray();
    if(sw1!=0x61)throw new SecurityException("Capability read unsupported");
    command=String.format(Locale.ROOT,"%02XC00000%02X",0x80|ch,sw2);
   }
   throw new SecurityException("Capability response budget exhausted");
  }finally{bridge.close();}
 }
 public static JSONObject read()throws Exception{
  if(android.os.Process.myUid()!=android.os.Process.SHELL_UID)throw new SecurityException("Shell UserService required");
  FiveberReaderSelector.Target before=FiveberReaderSelector.discover();
  byte[] fullEidResponse=query(false,before.reader);
  String masked=CardCapabilitiesParser.maskedEid(fullEidResponse,before.identityHash);
  JSONObject result=new JSONObject().put("eidMasked",masked).put("capabilitiesSupported",false);
  try{
   byte[] info2=query(true,before.reader);
   Map<String,Object> info=CardCapabilitiesParser.parse(info2);
   for(Map.Entry<String,Object> entry:info.entrySet())result.put(entry.getKey(),entry.getValue());
   result.put("capabilitiesSupported",true);
  }catch(Exception unsupported){result.put("capabilitiesStatus","unavailable");}
  FiveberReaderSelector.Target after=FiveberReaderSelector.discover();
  if(!before.reader.equals(after.reader)||!before.identityHash.equals(after.identityHash))throw new SecurityException("Card identity changed during capability read");
  if(result.optBoolean("capabilitiesSupported"))result.put("capabilitiesStatus","available");
  return result;
 }
}
