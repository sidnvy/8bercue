package dev.sid.fiveberinspector;

import java.io.*;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/** Deterministic mock Crypto proves transport framing/state, NOT SPAKE2 or TLS. */
public final class OnDeviceAdbPairingExchangeOfflineTest {
    static int assertions;
    static void verify(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        assertions++;
    }
    static final class FakeCrypto implements OnDeviceAdbPairingExchange.Crypto {
        boolean closed;
        boolean wrongPeer;
        FakeCrypto(boolean reject) {wrongPeer = reject;}
        public byte[] firstMessage() {return new byte[32];}
        public boolean acceptPeerMessage(byte[] peer) {return !wrongPeer && peer.length == 32;}
        public byte[] seal(byte[] plaintext) {
            byte[] out = new byte[plaintext.length + 16];
            System.arraycopy(plaintext, 0, out, 0, plaintext.length);
            return out;
        }
        public byte[] open(byte[] ciphertext) {
            byte[] peer=Arrays.copyOf(ciphertext,ciphertext.length-16);
            peer[1]='l';peer[2]='a';peer[3]='b';peer[4]=0;
            return peer;
        }
        public void close() {closed = true;}
    }
    static byte[] packet(int type, int size) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OnDeviceAdbPairingFrame.write(out, type, new byte[size]);
        return out.toByteArray();
    }
    static void fail(byte[] input, boolean wrongPeer) throws Exception {
        FakeCrypto crypto = new FakeCrypto(wrongPeer);
        try {
            OnDeviceAdbPairingExchange.exchange(new ByteArrayInputStream(input), new ByteArrayOutputStream(),
                "123456".toCharArray(), new byte[64], new byte[]{'x',0}, pwd -> crypto);
            throw new AssertionError("Invalid input unexpectedly succeeded");
        } catch (IOException expected) { verify(crypto.closed,"crypto closes on error"); }
    }
    public static void main(String[] args) throws Exception {
        verify(OnDeviceAdbPairingExchange.validCode("123456".toCharArray()),"six digit code");
        verify(!OnDeviceAdbPairingExchange.validCode("12345".toCharArray()),"short code rejected");
        verify(!OnDeviceAdbPairingExchange.validCode("1234567".toCharArray()),"long code rejected");
        verify(!OnDeviceAdbPairingExchange.validCode("12a456".toCharArray()),"non-digit code rejected");
        verify(!OnDeviceAdbPairingExchange.validCode(null),"null code rejected");
        try { OnDeviceAdbPairingExchange.sessionPassword("123456".toCharArray(),new byte[63]);throw new AssertionError(); }
        catch (IOException expected) { assertions++; }
        byte[] password = OnDeviceAdbPairingExchange.sessionPassword("012345".toCharArray(),new byte[64]);
        verify(password.length == 70 && password[0] == '0' && password[5] == '5',"code and exporter ordering");
        Arrays.fill(password,(byte)0);
        byte[] frame1=packet(OnDeviceAdbPairingFrame.TYPE_SPAKE2, 32);
        byte[] frame2=packet(OnDeviceAdbPairingFrame.TYPE_PEER_INFO, 8208);
        byte[] input = new byte[frame1.length+frame2.length];
        System.arraycopy(frame1,0,input,0,frame1.length);
        System.arraycopy(frame2,0,input,frame1.length,frame2.length);
        FakeCrypto success = new FakeCrypto(false);
        ByteArrayOutputStream written = new ByteArrayOutputStream();
        final boolean[] passwordWasScrubbed = {false};
        String guid=OnDeviceAdbPairingExchange.exchange(new ByteArrayInputStream(input),written,"123456".toCharArray(),
            new byte[64],new byte[]{'x',0},pwd -> {
                passwordWasScrubbed[0] = pwd.length == 70;
                return success;
            });
        verify("lab".equals(guid),"AEAD-authenticated device GUID");
        verify(success.closed,"crypto closes on success");
        verify(passwordWasScrubbed[0],"factory received bounded password");
        verify(written.size() == 6+32+6+8208,"both pairing messages emitted");
        fail(frame1, false); // missing peer exchange
        fail(input, true); // bad SPAKE2 response
        fail(packet(OnDeviceAdbPairingFrame.TYPE_PEER_INFO,8208), false); // stage confusion
        byte[] bad = new byte[frame1.length+packet(OnDeviceAdbPairingFrame.TYPE_PEER_INFO,30).length];
        System.arraycopy(frame1,0,bad,0,frame1.length);
        System.arraycopy(packet(OnDeviceAdbPairingFrame.TYPE_PEER_INFO,30),0,bad,frame1.length,bad.length-frame1.length);
        fail(bad,false);
        System.out.println("PASS "+assertions+" bounded ADB pairing exchange orchestrator assertions (mock crypto only)");
    }
}
