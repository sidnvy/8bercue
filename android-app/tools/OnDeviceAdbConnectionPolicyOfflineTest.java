package dev.sid.fiveberinspector;
import java.io.*;
import java.security.cert.*;
import java.nio.file.*;

/** Real self-signed certificates; protocol rejection is tested separately. */
public final class OnDeviceAdbConnectionPolicyOfflineTest {
    static int checks;
    static void ok(boolean pass){if(!pass)throw new AssertionError();checks++;}
    static X509Certificate cert(String path)throws Exception {
        try(InputStream in=Files.newInputStream(Path.of(path))){
            return (X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
    }
    static void verify(String protocol,Certificate[] peer,Certificate[] local, X509Certificate client)throws Exception {
        OnDeviceAdbConnectionPolicy.verify(protocol,peer,local,client.getPublicKey());checks++;
    }
    static void denied(String protocol,Certificate[] peer,Certificate[] local,X509Certificate client)throws Exception {
        try {verify(protocol,peer,local,client);throw new AssertionError("Invalid TLS identity accepted");}
        catch(CertificateException expected){checks++;}
    }
    public static void main(String[] args)throws Exception {
        X509Certificate first=cert(args[0]),renewed=cert(args[1]),restarted=cert(args[2]),client=cert(args[3]);
        ok(!java.util.Arrays.equals(first.getEncoded(),renewed.getEncoded()));
        ok(java.util.Arrays.equals(first.getPublicKey().getEncoded(),renewed.getPublicKey().getEncoded()));
        Certificate[] local={client};
        for(X509Certificate server:new X509Certificate[]{first,renewed,restarted})
            verify("TLSv1.3",new Certificate[]{server},local,client);
        denied("TLSv1.2",new Certificate[]{first},local,client);
        denied("TLSv1.3",null,local,client);
        denied("TLSv1.3",new Certificate[0],local,client);
        denied("TLSv1.3",new Certificate[]{first,renewed},local,client);
        denied("TLSv1.3",new Certificate[]{first},null,client);
        denied("TLSv1.3",new Certificate[]{first},new Certificate[0],client);
        denied("TLSv1.3",new Certificate[]{first},new Certificate[]{renewed},client);
        byte[] corrupted=first.getEncoded();corrupted[corrupted.length-1]^=1;
        X509Certificate invalid=(X509Certificate)CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(corrupted));
        denied("TLSv1.3",new Certificate[]{invalid},local,client);
        System.out.println("PASS "+checks+" real-certificate AOSP connection-policy checks (no live adbd)");
    }
}
