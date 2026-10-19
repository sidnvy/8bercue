package dev.sid.fiveberinspector;

import android.os.Build;
import android.os.Process;
import android.util.Log;
import dalvik.system.PathClassLoader;
import java.lang.reflect.InvocationTargetException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Read-only dynamic AIDL reader discovery. Ambiguity never selects a default slot. */
public final class FiveberReaderSelector {
    private static final String AIDL_PREFIX =
        "android.hardware.secure_element.ISecureElement/";
    private static final String ISDR_AID = "A0000005591010FFFFFFFF8900000100";
    static boolean isKnownCardHash(String digest){return ReaderAdmission.known(digest);}
    static String knownCardLabel(String digest){return ReaderAdmission.label(digest);}
    private static final byte[] GET_EID = {(byte)0xBF,0x3E,3,0x5C,1,0x5A};
    private static final int MAX_RESPONSE_BYTES = 256;
    private static final int MAX_FRAMES = 6;

    public static final class Target {
        public final String reader;
        public final String sha256Prefix;
        public final String identityHash;
        private Target(String reader,String prefix) {
            this.reader=reader;this.identityHash=prefix.toLowerCase(Locale.ROOT);this.sha256Prefix=prefix.substring(0,16);
        }
    }

    private FiveberReaderSelector() {}
    private static String upperHex(byte[] data) {
        StringBuilder sb=new StringBuilder(data.length*2);
        for(byte b:data) sb.append(String.format(Locale.ROOT,"%02X",b&255));
        return sb.toString();
    }
    private static byte[] fromHex(String str) {
        if(str==null || (str.length()%2)!=0)throw new SecurityException("Bad AID");
        byte[] r=new byte[str.length()/2];
        for(int i=0;i<r.length;i++)r[i]=(byte)Integer.parseInt(str.substring(2*i,2*i+2),16);
        return r;
    }
    private static Throwable unwrap(Throwable e) {
        while(e instanceof InvocationTargetException &&
                ((InvocationTargetException)e).getTargetException()!=null)
            e=((InvocationTargetException)e).getTargetException();
        return e;
    }
    private static byte[] readEid(Object hal,Class<?> api,int ch) throws Exception {
        byte[] cmd = new byte[5+GET_EID.length];
        cmd[0]=(byte)(0x80|ch);cmd[1]=(byte)0xE2;cmd[2]=(byte)0x91;
        cmd[3]=0;cmd[4]=(byte)GET_EID.length;
        System.arraycopy(GET_EID,0,cmd,5,GET_EID.length);
        java.io.ByteArrayOutputStream body=new java.io.ByteArrayOutputStream();
        for(int i=0;i<MAX_FRAMES;i++) {
            byte[] reply=(byte[])api.getMethod("transmit",byte[].class)
                .invoke(hal,(Object)cmd);
            if(reply==null || reply.length<2 || body.size()+reply.length>MAX_RESPONSE_BYTES)
                throw new SecurityException("Unexpected GetEID reply length");
            body.write(reply,0,reply.length-2);
            int sw1=reply[reply.length-2]&255, sw2=reply[reply.length-1]&255;
            if(sw1==0x90&&sw2==0)break;
            if(sw1!=0x61 || i==MAX_FRAMES-1)
                throw new SecurityException("GetEID status/continuation rejected");
            cmd=new byte[]{(byte)(0x80|ch),(byte)0xC0,0,0,(byte)sw2};
        }
        byte[] data=body.toByteArray();
        if(data.length!=21 ||
            (data[0]&255)!=0xBF || (data[1]&255)!=0x3E ||
            (data[2]&255)!=0x12 || (data[3]&255)!=0x5A ||
            (data[4]&255)!=0x10)
            throw new SecurityException("Invalid BER eUICC GetEID response");
        return Arrays.copyOfRange(data,5,21);
    }
    private static String digestPrefix(byte[] eid) throws Exception {
        byte[] h=MessageDigest.getInstance("SHA-256").digest(eid);
        return upperHex(h);
    }
    private static Target inspectOne(Class<?> manager,Class<?> binderType,
          Class<?> iface,Class<?> stub,String reader,String pinnedHash) {
        Object hal=null;
        int channel=-1;
        String phase="service_lookup";
        try {
            Object binder=manager.getMethod("getService",String.class)
                .invoke(null,AIDL_PREFIX+reader);
            if(binder==null)return null;
            phase="card_presence";
            hal=stub.getMethod("asInterface",binderType).invoke(null,binder);
            boolean present=(Boolean)iface.getMethod("isCardPresent").invoke(hal);
            if(!present)return null;
            phase="open_channel";
            Object opened=iface.getMethod("openLogicalChannel",byte[].class,byte.class)
                .invoke(hal,fromHex(ISDR_AID),(byte)0);
            channel=opened.getClass().getField("channelNumber").getInt(opened);
            if(channel<1 || channel>3)
                throw new SecurityException("Unexpected logical channel");
            phase="eid_read";
            String digest=digestPrefix(readEid(hal,iface,channel));
            if(ReaderAdmission.accepts(digest))
                return new Target(reader,digest);
            Log.w("EightberReaderPilot", "reader="+reader+" phase=invalid_eid_identity");
            return null;
        }catch(Throwable ignored) {
            // Reader class / stage only: never log APDUs, full EIDs or
            // exception messages. This distinguishes denial from slot absence.
            Throwable cause=unwrap(ignored);
            Log.w("EightberReaderPilot", "reader="+reader+
                " phase="+phase+" error="+cause.getClass().getSimpleName());
            return null;
        }finally{
            if(channel>0 && hal!=null) {
                try {iface.getMethod("closeChannel",byte.class)
                    .invoke(hal,(byte)channel);}
                catch(Throwable ignored) {
                    throw new SecurityException("Reader channel cleanup failed");
                }
            }
        }
    }

    // Discovery only. Every mutation separately verifies caller, intent and full identity.
    public static Target discover() throws Exception { return discoverInternal(false); }

    // Compatibility entry point for the independent read-only lab inventory.
    // It shares generic discovery; the lab inventory may further restrict its display.
    public static Target discoverKnownCardReadOnly() throws Exception {
        return discoverInternal(true);
    }

    private static Target discoverInternal(boolean crossDeviceReadOnly) throws Exception {
        if(Process.myUid()!=Process.SHELL_UID)
            throw new SecurityException("Requires authorized ADB Shell UID2000");
        if(Build.VERSION.SDK_INT<36)throw new SecurityException("Unsupported Android build");
        String expected=null; // Reader discovery does not authorize a mutation.
        Class<?> manager=Class.forName("android.os.ServiceManager");
        Class<?> binder=Class.forName("android.os.IBinder");
        ClassLoader loader=new PathClassLoader(SystemHalAdapter.dexPath(),
            FiveberReaderSelector.class.getClassLoader());
        Class<?> iface=Class.forName(
            "android.hardware.secure_element.ISecureElement",true,loader);
        Class<?> stub=Class.forName(
            "android.hardware.secure_element.ISecureElement$Stub",true,loader);
        List<Target> matches=new ArrayList<>();
        String[] services=(String[])manager.getMethod("listServices").invoke(null);
        for(String reader:ReaderDescriptor.instances(services)) {
            Target found=inspectOne(manager,binder,iface,stub,reader,expected);
            if(found!=null)matches.add(found);
        }
        if(matches.size()!=1)
            throw new SecurityException("Select exactly one readable eUICC; ambiguous selection is denied");
        return matches.get(0);
    }

}
