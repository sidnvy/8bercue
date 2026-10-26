package dev.sid.fiveberinspector;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Base64;
public final class OnDeviceAdbPublicKeyOfflineTest {
    static int checks;
    static void verify(boolean ok,String label) {if(!ok)throw new AssertionError(label);checks++;}
    public static void main(String[] args) throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");gen.initialize(2048);
        KeyPair pair=gen.generateKeyPair();
        RSAPublicKey publicKey=(RSAPublicKey)pair.getPublic();
        byte[] encoded=OnDeviceAdbPublicKey.binary(publicKey);
        verify(encoded.length==524,"full AOSP pubkey length");
        ByteBuffer fields=ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        verify(fields.getInt()==64,"RSA modulus 64 words");
        int n0inv=fields.getInt();
        BigInteger low32=publicKey.getModulus().mod(BigInteger.ONE.shiftLeft(32));
        verify(low32.multiply(BigInteger.valueOf(n0inv&0xffffffffL)).mod(BigInteger.ONE.shiftLeft(32))
                .equals(BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE)),"Montgomery inverse");
        byte[] bytes=new byte[256];fields.get(bytes);
        for(int i=0;i<128;i++){byte tmp=bytes[i];bytes[i]=bytes[255-i];bytes[255-i]=tmp;}
        verify(new BigInteger(1,bytes).equals(publicKey.getModulus()),"little endian modulus");
        byte[] rrBytes=new byte[256];fields.get(rrBytes);
        for(int i=0;i<128;i++){byte tmp=rrBytes[i];rrBytes[i]=rrBytes[255-i];rrBytes[255-i]=tmp;}
        verify(new BigInteger(1,rrBytes).equals(BigInteger.ONE.shiftLeft(4096).mod(publicKey.getModulus())),"Montgomery R squared");
        verify(fields.getInt()==65537,"RSA exponent");
        byte[] pairInfo=OnDeviceAdbPublicKey.pairInfo(publicKey);
        String info=new String(pairInfo,StandardCharsets.US_ASCII);
        verify(info.endsWith(" 8bercue@android\0"),"ADB identity label is NUL terminated");
        byte[] binary=Base64.getDecoder().decode(info.substring(0,info.indexOf(' ')));
        verify(Arrays.equals(encoded,binary),"PeerInfo public key base64 roundtrip");
        System.out.println("PASS "+checks+" AOSP 2048-bit ADB public-key/Keystore identity format checks");
    }
}
