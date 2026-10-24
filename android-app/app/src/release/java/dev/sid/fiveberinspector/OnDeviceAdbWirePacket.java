package dev.sid.fiveberinspector;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Bounded ADB wire-framing implementation for the eventual restricted STLS
 * client. Packet syntax validation does NOT authenticate an adbd peer.
 * Access to the ADB shell must remain limited to the signed app's fixed
 * Connect entrypoint, with explicit verified Binder handoff.
 */
final class OnDeviceAdbWirePacket implements AutoCloseable {
    static final int HEADER_LENGTH = 24;
    static final int MAX_PAYLOAD = 1024 * 1024;
    static final int A_CNXN = 0x4e584e43;
    static final int A_AUTH = 0x48545541;
    static final int A_OPEN = 0x4e45504f;
    static final int A_OKAY = 0x59414b4f;
    static final int A_CLSE = 0x45534c43;
    static final int A_WRTE = 0x45545257;
    static final int A_STLS = 0x534c5453;
    static final int A_VERSION = 0x01000000;
    static final int A_STLS_VERSION = 0x01000000;

    final int command;
    final int arg0;
    final int arg1;
    final byte[] payload;

    private OnDeviceAdbWirePacket(int command, int arg0, int arg1, byte[] payload) {
        this.command = command;
        this.arg0 = arg0;
        this.arg1 = arg1;
        this.payload = payload;
    }

    private static boolean known(int command) {
        switch (command) {
            case A_CNXN: case A_AUTH: case A_OPEN: case A_OKAY:
            case A_CLSE: case A_WRTE: case A_STLS: return true;
            default: return false;
        }
    }

    static int sum(byte[] bytes) {
        int checksum = 0;
        for (byte b : bytes) checksum += b & 0xff;
        return checksum;
    }

    static OnDeviceAdbWirePacket read(InputStream stream) throws IOException {
        byte[] header = new byte[HEADER_LENGTH];
        exact(stream, header);
        ByteBuffer fields = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int command = fields.getInt();
        int arg0 = fields.getInt();
        int arg1 = fields.getInt();
        int length = fields.getInt();
        int checksum = fields.getInt();
        int magic = fields.getInt();
        if (!known(command) || magic != (command ^ -1) || length < 0 || length > MAX_PAYLOAD)
            throw new IOException("Malformed ADB packet");
        byte[] payload = new byte[length];
        try {
            exact(stream, payload);
            // Android ADB v1 uses an unsigned-byte additive checksum.
            if (sum(payload) != checksum) throw new IOException("ADB packet checksum mismatch");
            return new OnDeviceAdbWirePacket(command, arg0, arg1, payload);
        } catch (IOException error) {
            Arrays.fill(payload, (byte) 0);
            throw error;
        }
    }

    static void write(OutputStream stream, int command, int arg0, int arg1, byte[] data)
            throws IOException {
        if (!known(command) || data == null || data.length > MAX_PAYLOAD)
            throw new IOException("Unrecognized or oversized ADB packet");
        ByteBuffer header = ByteBuffer.allocate(HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(command).putInt(arg0).putInt(arg1).putInt(data.length)
                .putInt(sum(data)).putInt(command ^ -1);
        stream.write(header.array());
        stream.write(data);
        stream.flush();
    }

    static void exact(InputStream in, byte[] dest) throws IOException {
        int pos = 0;
        while (pos < dest.length) {
            int len = in.read(dest, pos, dest.length - pos);
            if (len < 0) throw new EOFException("Truncated ADB packet");
            if (len == 0) throw new IOException("ADB read made no progress");
            pos += len;
        }
    }

    @Override public void close() { Arrays.fill(payload, (byte) 0); }
}
