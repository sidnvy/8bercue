package dev.sid.fiveberinspector;

import java.io.*;
import java.util.Arrays;

public final class OnDeviceAdbRestrictedClientOfflineTest {
    static int checks;
    static void ok(boolean valid,String label){if(!valid)throw new AssertionError(label);checks++;}
    static final class FakeTls implements OnDeviceAdbRestrictedClient.AuthenticatedStream {
        final ByteArrayInputStream input;final ByteArrayOutputStream output=new ByteArrayOutputStream();
        boolean verified=true;boolean closed;
        FakeTls(byte[] bytes){input=new ByteArrayInputStream(bytes);}
        public InputStream input(){return input;}
        public OutputStream output(){return output;}
        public boolean clientIdentityPresented(){return verified;}
        public void close(){closed=true;}
    }
    static byte[] concat(byte[]... parts){
        int size=0;for(byte[] b:parts)size+=b.length;
        byte[] combined=new byte[size];int offset=0;
        for(byte[] b:parts){System.arraycopy(b,0,combined,offset,b.length);offset+=b.length;}
        return combined;
    }
    static byte[] frame(int cmd,int arg0,int arg1,byte[] bytes)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        OnDeviceAdbWirePacket.write(out,cmd,arg0,arg1,bytes);return out.toByteArray();
    }
    static final byte[] ZERO=new byte[0];
    static final byte[] DEVICE="device::\u0000".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    static final String APK="/data/app/~~abc_123/dev.sid.fiveberinspector-XyZ/base.apk";
    static byte[] greeting() throws IOException {
        return frame(OnDeviceAdbWirePacket.A_STLS,OnDeviceAdbWirePacket.A_STLS_VERSION,0,ZERO);
    }
    static byte[] goodTls() throws IOException { return goodTls(DEVICE); }
    static byte[] goodTls(byte[] banner) throws IOException {
        return concat(
          frame(OnDeviceAdbWirePacket.A_CNXN,OnDeviceAdbWirePacket.A_VERSION,4096,banner),
          frame(OnDeviceAdbWirePacket.A_OKAY,2,1,ZERO),
          frame(OnDeviceAdbWirePacket.A_WRTE,2,1,new byte[]{1,2}),
          frame(OnDeviceAdbWirePacket.A_CLSE,2,1,ZERO));
    }
    static void fails(byte[] start,FakeTls tls) throws Exception {
        try {OnDeviceAdbRestrictedClient.run(new ByteArrayInputStream(start),
              new ByteArrayOutputStream(),()->tls,APK);throw new AssertionError("invalid accepted");}
        catch(IOException expected){checks++;}
    }
    public static void main(String[] args) throws Exception {
        String command=OnDeviceAdbRestrictedClient.fixedEntrypoint(APK);
        ok(command.startsWith("shell:CLASSPATH="),"fixed command");
        ok(command.endsWith("dev.sid.fiveberinspector.Connect\u0000"),"no generic command");
        for(String path:new String[]{"/tmp/app.apk","/data/app/evil;ls/base.apk","../base.apk",null}){
            try{OnDeviceAdbRestrictedClient.fixedEntrypoint(path);throw new AssertionError();}
            catch(IOException e){checks++;}
        }
        FakeTls tls=new FakeTls(goodTls());
        ByteArrayOutputStream outgoing=new ByteArrayOutputStream();
        OnDeviceAdbRestrictedClient.run(new ByteArrayInputStream(greeting()),outgoing,()->tls,APK);
        ok(tls.closed,"close authenticated TLS after completion");
        try(OnDeviceAdbWirePacket hello=OnDeviceAdbWirePacket.read(new ByteArrayInputStream(outgoing.toByteArray()))) {
            ok(hello.command==OnDeviceAdbWirePacket.A_CNXN,"hello starts before TLS");
        }
        byte[] tlsOutput=tls.output.toByteArray();
        try(OnDeviceAdbWirePacket open=OnDeviceAdbWirePacket.read(new ByteArrayInputStream(tlsOutput), true)) {
            ok(open.command==OnDeviceAdbWirePacket.A_OPEN,"restricted OPEN after TLS");
            ok(new String(open.payload,java.nio.charset.StandardCharsets.US_ASCII).equals(command),"only verified command opened");
        }
        // AOSP adb.cpp send_connect() copies a length-delimited std::string;
        // the actual non-terminated banner must get past CNXN and open Connect.
        for (String banner:new String[]{"device::", "device::ro.product.name=fixture;features=shell_v2", "device::features=shell_v2\u0000"}) {
            FakeTls compatible=new FakeTls(goodTls(banner.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
            OnDeviceAdbRestrictedClient.run(new ByteArrayInputStream(greeting()),new ByteArrayOutputStream(),()->compatible,APK);
            try (OnDeviceAdbWirePacket open=OnDeviceAdbWirePacket.read(new ByteArrayInputStream(compatible.output.toByteArray()),true)) {
                ok(open.command==OnDeviceAdbWirePacket.A_OPEN,"length-delimited or legacy banner opens fixed command");
            }
        }
        byte[] oversized=new byte[4097];Arrays.fill(oversized,(byte)'a');
        System.arraycopy("device::".getBytes(java.nio.charset.StandardCharsets.US_ASCII),0,oversized,0,8);
        for (byte[] banner:new byte[][]{"device:".getBytes(), "host::features=shell_v2".getBytes(),
                "device::features=a\u0000junk".getBytes(), oversized}) {
            FakeTls denied=new FakeTls(goodTls(banner));fails(greeting(),denied);
            ok(denied.output.size()==0,"invalid/oversized role banner never opens Connect");
        }
        fails(frame(OnDeviceAdbWirePacket.A_AUTH,1,0,ZERO),new FakeTls(goodTls()));
        fails(frame(OnDeviceAdbWirePacket.A_STLS,0,0,ZERO),new FakeTls(goodTls()));
        FakeTls unauth=new FakeTls(goodTls());unauth.verified=false;fails(greeting(),unauth);
        ok(unauth.closed,"TLS closed after untrusted paired identity");
        fails(greeting(),new FakeTls(frame(OnDeviceAdbWirePacket.A_AUTH,1,0,ZERO)));
        fails(greeting(),new FakeTls(frame(OnDeviceAdbWirePacket.A_CNXN,OnDeviceAdbWirePacket.A_VERSION,4096,DEVICE)));
        fails(greeting(),new FakeTls(concat(frame(OnDeviceAdbWirePacket.A_CNXN,OnDeviceAdbWirePacket.A_VERSION,4096,DEVICE),frame(OnDeviceAdbWirePacket.A_OKAY,2,9,ZERO))));
        fails(greeting(),new FakeTls(concat(frame(OnDeviceAdbWirePacket.A_CNXN,OnDeviceAdbWirePacket.A_VERSION,4096,DEVICE),frame(OnDeviceAdbWirePacket.A_OKAY,2,1,ZERO),frame(OnDeviceAdbWirePacket.A_CLSE,3,1,ZERO))));
        for (byte[] rejected : new byte[][]{ZERO,
                frame(OnDeviceAdbWirePacket.A_AUTH,1,0,ZERO),
                frame(OnDeviceAdbWirePacket.A_CNXN,OnDeviceAdbWirePacket.A_VERSION,0,ZERO),
                frame(OnDeviceAdbWirePacket.A_CNXN,0,4096,ZERO)}) {
            FakeTls denied=new FakeTls(rejected);
            fails(greeting(),denied);
            ok(denied.output.size()==0,"No OPEN before valid post-handshake CNXN");
        }
        fails(greeting(),new FakeTls(frame(OnDeviceAdbWirePacket.A_CNXN,OnDeviceAdbWirePacket.A_VERSION,4096,ZERO)));
        fails(greeting(),new FakeTls(frame(OnDeviceAdbWirePacket.A_CNXN,OnDeviceAdbWirePacket.A_VERSION,4096,"host::\u0000".getBytes(java.nio.charset.StandardCharsets.US_ASCII))));
        fails(frame(OnDeviceAdbWirePacket.A_STLS,OnDeviceAdbWirePacket.A_STLS_VERSION,1,ZERO),new FakeTls(goodTls()));
        try {
            OnDeviceAdbRestrictedClient.run(new ByteArrayInputStream(greeting()),new ByteArrayOutputStream(),
                ()->{throw new javax.net.ssl.SSLHandshakeException("client rejected");},APK);
            throw new AssertionError("TLS rejection accepted");
        } catch(javax.net.ssl.SSLHandshakeException expected){checks++;}
        System.out.println("PASS "+checks+" restricted ADB STLS protocol-state assertions (mock TLS only)");
    }
}
