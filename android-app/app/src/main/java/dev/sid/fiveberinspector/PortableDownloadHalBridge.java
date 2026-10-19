package dev.sid.fiveberinspector;

import android.os.Process;
import dalvik.system.PathClassLoader;
import org.json.JSONObject;

import java.lang.reflect.InvocationTargetException;
import java.util.Locale;

/** Transaction-bound HAL transport: discovered reader and enrolled card, with staged ES10b only.
 * Enable, disable, nickname, delete and reset are never admitted by this download bridge.
 */
public final class PortableDownloadHalBridge implements AutoCloseable {
    private static final String AID = "A0000005591010FFFFFFFF8900000100";
    private final DownloadSessionPolicy policy = new DownloadSessionPolicy();
    private final String reader, hash;
    public PortableDownloadHalBridge(String reader,String hash) {this.reader=reader;this.hash=hash;}
    private Object hal;
    private Class<?> api;
    private int channel = -1;
    private boolean connected;

    static byte[] unhex(String value) {
        if (value == null || (value.length() & 1) != 0 || value.length() > 1024)
            throw new SecurityException("Invalid HAL APDU hex length");
        byte[] bytes = new byte[value.length() / 2];
        for (int i=0;i<bytes.length;i++)
            bytes[i]=(byte)Integer.parseInt(value.substring(2*i,2*i+2),16);
        return bytes;
    }
    static String hex(byte[] bytes) {
        StringBuilder sb=new StringBuilder(bytes.length*2);
        for(byte b:bytes)sb.append(String.format(Locale.ROOT,"%02X",b&255));
        return sb.toString();
    }
    static Throwable root(Throwable err) {
        while(err instanceof InvocationTargetException &&
            ((InvocationTargetException)err).getTargetException()!=null)
                err=((InvocationTargetException)err).getTargetException();
        return err;
    }
    private static JSONObject reply(int code, String data) throws Exception {
        JSONObject p=new JSONObject().put("ecode",code);
        if(data!=null)p.put("data",data);
        return new JSONObject().put("type","apdu").put("payload",p);
    }

    private void connect() throws Exception {
        if(connected)return;
        if(Process.myUid()!=Process.SHELL_UID)
            throw new SecurityException("Requires authorized Shell identity");
        FiveberReaderSelector.Target verified=FiveberReaderSelector.discover();
        if(!reader.equals(verified.reader) || !hash.equals(verified.identityHash))
            throw new SecurityException("Download card identity changed");
        Class<?> mgr=Class.forName("android.os.ServiceManager");
        Class<?> binderClass=Class.forName("android.os.IBinder");
        Object binder=mgr.getMethod("getService",String.class).invoke(null,
            "android.hardware.secure_element.ISecureElement/"+reader);
        if(binder==null)throw new IllegalStateException("HAL SIM1 absent");
        ClassLoader loader=new PathClassLoader(
            SystemHalAdapter.dexPath(),
            PortableDownloadHalBridge.class.getClassLoader());
        api=Class.forName("android.hardware.secure_element.ISecureElement",true,loader);
        Class<?> stub=Class.forName("android.hardware.secure_element.ISecureElement$Stub",true,loader);
        hal=stub.getMethod("asInterface",binderClass).invoke(null,binder);
        if(!(Boolean)api.getMethod("isCardPresent").invoke(hal))
            throw new IllegalStateException("Removable eUICC is not present");
        connected=true;
    }
    private int open(String candidate) throws Exception {
        if (!AID.equals(candidate) || channel > 0)
            throw new SecurityException("Unexpected GSMA ISD-R AID or duplicate open");
        connect();
        Object result=api.getMethod("openLogicalChannel",byte[].class,byte.class)
            .invoke(hal,unhex(AID),(byte)0);
        channel=result.getClass().getField("channelNumber").getInt(result);
        if(channel<1 || channel>3)throw new SecurityException("Unexpected logical channel");
        return channel;
    }
    public JSONObject handle(JSONObject request) throws Exception {
        if (!"apdu".equals(request.getString("type")))
            throw new SecurityException("Expected lpac APDU request");
        JSONObject p=request.getJSONObject("payload");
        String fn=p.getString("func");
        String param=p.isNull("param")?"":p.optString("param","").toUpperCase(Locale.ROOT);
        switch(fn) {
            case "connect":
                connect();
                return reply(0,null);
            case "logic_channel_open":
                return reply(open(param),null);
            case "logic_channel_close":
            case "disconnect":
                close();
                return reply(0,null);
            case "transmit":
                if(channel<1 || channel>3)
                    throw new SecurityException("No open card channel");
                byte[] command=unhex(param);
                policy.authorize(command,channel);
                byte[] response=(byte[])api.getMethod("transmit",byte[].class)
                    .invoke(hal,(Object)command);
                if(response==null || response.length<2 || response.length>8192)
                    throw new SecurityException("Invalid card response");
                policy.acceptResponse(command,channel,response);
                return reply(0,hex(response));
            default:
                throw new SecurityException("HAL operation not allowed");
        }
    }
    public DownloadSessionPolicy.Stage stage() {return policy.stage();}
    public int installCommands() {return policy.installCommands();}
    public int frames() {return policy.commandFrames();}
    public void bindTransaction(String id) {policy.bindTransaction(id);}
    public void authorizeCancellationHttp(String id) {policy.authorizeCancellationHttp(id);}
    public void authorizeHttp(String operation) {policy.authorizeHttp(operation);}
    public void httpCompleted(String operation,int status) {
        policy.httpCompleted(operation,status);
    }
    @Override public void close() {
        if(channel>0 && api!=null && hal!=null) {
            try {
                api.getMethod("closeChannel",byte.class).invoke(hal,(byte)channel);
            } catch(Exception ignored) {
                // No response/log content containing card identifiers.
            }
        }
        channel=-1;connected=false;
    }
}
