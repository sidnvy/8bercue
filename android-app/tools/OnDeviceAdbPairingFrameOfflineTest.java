package dev.sid.fiveberinspector;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public final class OnDeviceAdbPairingFrameOfflineTest {
    static int checked;
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checked++;
    }
    private static void invalid(byte[] wire, String label) {
        try (OnDeviceAdbPairingFrame frame = OnDeviceAdbPairingFrame.read(new ByteArrayInputStream(wire))) {
            throw new AssertionError("Unexpectedly accepted: " + label);
        } catch (IOException expected) { checked++; }
    }
    private static byte[] header(int version, int type, int size) {
        return ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN)
            .put((byte) version).put((byte) type).putInt(size).array();
    }
    public static void main(String[] args) throws Exception {
        byte[] pairing = new byte[] {12, 45, 8, 51};
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        OnDeviceAdbPairingFrame.write(output, OnDeviceAdbPairingFrame.TYPE_SPAKE2, pairing);
        check(Arrays.equals(Arrays.copyOfRange(output.toByteArray(), 0, 6), header(1, 0, 4)), "big endian header");
        try (OnDeviceAdbPairingFrame read = OnDeviceAdbPairingFrame.read(new ByteArrayInputStream(output.toByteArray()))) {
            check(read.type == 0 && Arrays.equals(read.payload, pairing), "SPAKE2 frame round trip");
            read.close();
            check(Arrays.equals(read.payload, new byte[4]), "payload erased on close");
        }
        output.reset();
        OnDeviceAdbPairingFrame.write(output, OnDeviceAdbPairingFrame.TYPE_PEER_INFO, new byte[8208]);
        try (OnDeviceAdbPairingFrame read = OnDeviceAdbPairingFrame.read(new ByteArrayInputStream(output.toByteArray()))) {
            check(read.type == 1 && read.payload.length == 8208, "peer encrypted frame round trip");
        }
        invalid(header(2, 0, 1), "unsupported version");
        invalid(header(1, 2, 1), "unsupported message type");
        invalid(header(1, 0, 0), "zero length");
        invalid(header(1, 1, -1), "negative length");
        invalid(header(1, 0, 16385), "oversized frame");
        invalid(new byte[0], "truncated header");
        invalid(header(1, 0, 10), "truncated payload");
        byte[] sizeOnly = header(1, 0, 1);
        invalid(sizeOnly, "missing payload");
        try {OnDeviceAdbPairingFrame.write(new ByteArrayOutputStream(), 3, pairing);throw new AssertionError();}
        catch (IOException expected) { checked++; }
        System.out.println("PASS " + checked + " bounded ADB TLS pairing framing assertions");
    }
}
