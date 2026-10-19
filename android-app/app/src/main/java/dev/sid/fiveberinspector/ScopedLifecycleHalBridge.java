package dev.sid.fiveberinspector;

import android.os.Process;
import dalvik.system.PathClassLoader;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Locale;

/** Single-use exact ES10c operation gate. No arbitrary APDU or HTTP execution. */
public final class ScopedLifecycleHalBridge implements AutoCloseable {
    private static final String AID="A0000005591010FFFFFFFF8900000100";
    private final String target;
    private final String expectedHash,nickname;
    private final ProfileLifecycleGuard.Operation operation;
    private final String expectedReader;
    private Object hal;
    private Class<?> api;
    private int channel=-1;
    private boolean consumed;
    private boolean awaitingGetResponse;
    private int expectedLe=-1;
    private int responseFrames;

    public ScopedLifecycleHalBridge(ProfileLifecycleGuard.Operation operation,String target,String nickname,String reader,String hash) {
        if(!ReaderDescriptor.valid(reader))throw new SecurityException("Reader not enrolled");
        this.operation=operation;this.target=target;this.nickname=nickname;this.expectedReader=reader;this.expectedHash=hash;
        ProfileLifecycleGuard.command(operation,target,nickname,1);
    }
    private static byte[] bcd(String iccid) {
        if(iccid==null || !iccid.matches("[0-9]{19,20}"))
            throw new SecurityException("Unexpected target Profile identifier length");
        byte[] result=new byte[10];
        for(int i=0;i<10;i++) {
            int lo=2*i<iccid.length()?iccid.charAt(2*i)-'0':15;
            int hi=2*i+1<iccid.length()?iccid.charAt(2*i+1)-'0':15;
            result[i]=(byte)(lo|(hi<<4));
        }
        return result;
    }
    private static byte[] hex(String s) {
        if(s==null || s.length()>1024 || (s.length()&1)!=0 || !s.matches("[0-9A-Fa-f]*"))throw new SecurityException("Invalid APDU encoding");
        byte[] result=new byte[s.length()/2];
        for(int i=0;i<result.length;i++)
            result[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);
        return result;
    }
    private static String asHex(byte[] b) {
        StringBuilder s=new StringBuilder();
        for(byte v:b)s.append(String.format(Locale.ROOT,"%02X",v&255));
        return s.toString();
    }
    private static JSONObject response(int code,String data)throws Exception {
        JSONObject p=new JSONObject().put("ecode",code);
        if(data!=null)p.put("data",data);
        return new JSONObject().put("type","apdu").put("payload",p);
    }
    private byte[] exactEnableApdu() {return ProfileLifecycleGuard.command(operation,target,nickname,channel);}
    private void connect() throws Exception {
        if(hal!=null)return;
        if(Process.myUid()!=Process.SHELL_UID)
            throw new SecurityException("Requires local Shell UserService");
        // Re-identify the card at the exact moment a mutation channel opens;
        // refusing a swapped SIM/reader even if the UI preview is stale.
        FiveberReaderSelector.Target enrolled=FiveberReaderSelector.discover();
        if(!EnrolledWritePolicy.isAllowed(enrolled.identityHash))
            throw new SecurityException("Device and card are not enrolled for writes");
        if(!expectedReader.equals(enrolled.reader)||!expectedHash.equals(enrolled.identityHash))
            throw new SecurityException("Card reader changed during transaction");
        Class<?> manager=Class.forName("android.os.ServiceManager");
        Class<?> binder=Class.forName("android.os.IBinder");
        Object handle=manager.getMethod("getService",String.class)
            .invoke(null,"android.hardware.secure_element.ISecureElement/"+expectedReader);
        if(handle==null)throw new SecurityException("Missing enrolled card reader");
        ClassLoader loader=new PathClassLoader(
            SystemHalAdapter.dexPath(),
            ScopedLifecycleHalBridge.class.getClassLoader());
        api=Class.forName("android.hardware.secure_element.ISecureElement",true,loader);
        Class<?> stub=Class.forName("android.hardware.secure_element.ISecureElement$Stub",true,loader);
        hal=stub.getMethod("asInterface",binder).invoke(null,handle);
        if(!(Boolean)api.getMethod("isCardPresent").invoke(hal))
            throw new SecurityException("Card absent");
    }
    public JSONObject handle(JSONObject request)throws Exception {
        if(!"apdu".equals(request.getString("type")))
            throw new SecurityException("Wrong native protocol");
        JSONObject p=request.getJSONObject("payload");
        String fn=p.getString("func");
        String param=p.isNull("param")?"":p.optString("param","").toUpperCase(Locale.ROOT);
        switch(fn){
            case "connect":connect();return response(0,null);
            case "logic_channel_open":
                if(!AID.equals(param)||channel>0||consumed)
                    throw new SecurityException("Unexpected management applet");
                connect();
                Object opened=api.getMethod("openLogicalChannel",byte[].class,byte.class)
                    .invoke(hal,hex(AID),(byte)0);
                channel=opened.getClass().getField("channelNumber").getInt(opened);
                if(channel<1||channel>3)throw new SecurityException("Bad logical channel");
                return response(channel,null);
            case "transmit":
                if(channel<1||channel>3)throw new SecurityException("No open logical channel");
                byte[] actual=hex(param);
                if(!consumed) {
                    byte[] expected=exactEnableApdu();
                    if(!Arrays.equals(expected,actual))
                        throw new SecurityException("ES10c command differs from confirmed operation");
                    consumed=true;
                } else {
                    if(++responseFrames>6)
                        throw new SecurityException("profile operation continuation budget exhausted");
                    byte[] getResponse=new byte[]{
                        (byte)(0x80|channel),(byte)0xC0,0,0,(byte)expectedLe
                    };
                    if(!awaitingGetResponse || expectedLe<0 ||
                        !Arrays.equals(getResponse,actual))
                        throw new SecurityException("Unsolicited or malformed GET RESPONSE");
                }
                byte[] reply=(byte[])api.getMethod("transmit",byte[].class)
                    .invoke(hal,(Object)actual);
                if(reply==null||reply.length<2||reply.length>8192)
                    throw new SecurityException("Malformed card response");
                awaitingGetResponse=(reply[reply.length-2]&255)==0x61;
                expectedLe=awaitingGetResponse ? reply[reply.length-1]&255 : -1;
                return response(0,asHex(reply));
            case "logic_channel_close":
            case "disconnect":
                close();
                return response(0,null);
            default:
                throw new SecurityException("HAL operation is not allowed");
        }
    }
    public boolean wasSubmitted() {return consumed;}
    @Override public void close() {
        if(channel>0 && api!=null && hal!=null){
            try {api.getMethod("closeChannel",byte.class).invoke(hal,(byte)channel);}
            catch(Exception ignored){}
        }
        channel=-1;
    }
}
