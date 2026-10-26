package dev.sid.fiveberinspector;

import android.os.Process;
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
    private SecureElementTransport hal;
    private int channel=-1;
    private boolean consumed;
    private boolean awaitingGetResponse;
    private int expectedLe=-1;
    private int responseFrames;
    private boolean refreshCleanupFailed;

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
        if(expectedReader.startsWith(ReaderDescriptor.TELEPHONY_PREFIX)) {
            // Recheck disabled state and full target immediately before the mutation channel opens.
            ProfileLifecycleGuard.Row selected=ProfileLifecycleGuard.selected(PortableProfileLifecycle.rows(CardProfileInventory.list()),target);
            ReaderDescriptor.requireLifecycle(expectedReader,operation,selected.enabled);
            ShellOperationScope.validateMutation(expectedHash,operation,PortableProfileLifecycle.rows(CardProfileInventory.list()),target);
        } else ReaderDescriptor.requireWritable(expectedReader);
        hal=SystemHalAdapter.connectForLifecycle(expectedReader,operation,target,nickname);
        if(!hal.isCardPresent())
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
                channel=hal.openLogicalChannel(hex(AID),(byte)0);
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
                byte[] reply=hal.transmit(actual);
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
    boolean refreshCleanupFailed() {return refreshCleanupFailed;}
    @Override public void close() {
        if(channel>0 && hal!=null){
            // Consume ownership before closing: REFRESH may invalidate/reuse the number.
            // Never retry a close against a later client's channel.
            int owned=channel;channel=-1;
            try {hal.closeChannel(owned);}
            catch(Exception cleanupFailure){
                if(consumed&&(operation==ProfileLifecycleGuard.Operation.ENABLE
                        ||operation==ProfileLifecycleGuard.Operation.DISABLE))refreshCleanupFailed=true;
                else CardTransactionLock.quarantine();
            }
        }
        channel=-1;
    }
}
