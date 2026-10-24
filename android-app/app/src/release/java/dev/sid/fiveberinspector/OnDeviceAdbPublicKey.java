package dev.sid.fiveberinspector;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

/** Android ADB 2048-bit RSA public key encoder (AOSP android_pubkey format). */
final class OnDeviceAdbPublicKey {
    static final int RSA_BITS = 2048;
    static final int MODULUS_BYTES = RSA_BITS / 8;
    static final int PUBKEY_SIZE = 4 + 4 + MODULUS_BYTES + MODULUS_BYTES + 4;
    private static final BigInteger TWO_32 = BigInteger.ONE.shiftLeft(32);

    private OnDeviceAdbPublicKey() {}

    private static byte[] paddedLittleEndian(BigInteger number) throws IOException {
        if (number.signum() < 0 || number.bitLength() > RSA_BITS)
            throw new IOException("RSA key component out of range");
        byte[] out = new byte[MODULUS_BYTES];
        byte[] big = number.toByteArray();
        int useful = big.length;
        if (useful > MODULUS_BYTES && big[0] == 0) useful--;
        if (useful > MODULUS_BYTES) throw new IOException("RSA key component too long");
        for (int i=0;i<useful;i++) out[i] = big[big.length - 1 - i];
        return out;
    }

    static byte[] binary(RSAPublicKey key) throws IOException {
        if (key == null || key.getModulus().bitLength() != RSA_BITS
                || !key.getModulus().testBit(0)
                || !BigInteger.valueOf(65537).equals(key.getPublicExponent()))
            throw new IOException("Expected 2048-bit ADB RSA key with exponent 65537");
        BigInteger n = key.getModulus();
        ByteBuffer pub = ByteBuffer.allocate(PUBKEY_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        pub.putInt(MODULUS_BYTES / 4);
        pub.putInt(TWO_32.subtract(n.mod(TWO_32).modInverse(TWO_32)).intValue());
        pub.put(paddedLittleEndian(n));
        BigInteger r = BigInteger.ONE.shiftLeft(RSA_BITS);
        pub.put(paddedLittleEndian(r.multiply(r).mod(n)));
        pub.putInt(65537);
        return pub.array();
    }

    static byte[] pairInfo(RSAPublicKey key) throws IOException {
        // NUL terminated, safe printable label and no private key data.
        String text = Base64.getEncoder().encodeToString(binary(key)) + " 8bercue@android\u0000";
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}
