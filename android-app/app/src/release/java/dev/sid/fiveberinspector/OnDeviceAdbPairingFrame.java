package dev.sid.fiveberinspector;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Bounded framing for AOSP's TLS-protected ADB pairing protocol.
 *
 * No secrets, keys, pairing code or sockets are handled here. The cryptographic
 * exchange (TLS exporter, SPAKE2 and authenticated peer exchange) must be
 * implemented separately and never be inferred from successful frame parsing.
 */
final class OnDeviceAdbPairingFrame implements AutoCloseable {
    static final int VERSION = 1;
    static final int TYPE_SPAKE2 = 0;
    static final int TYPE_PEER_INFO = 1;
    static final int HEADER_BYTES = 6;
    static final int MAX_PEER_INFO_BYTES = 8192;
    static final int MAX_FRAME_BYTES = MAX_PEER_INFO_BYTES * 2;

    final int type;
    final byte[] payload;

    private OnDeviceAdbPairingFrame(int type, byte[] payload) {
        this.type = type;
        this.payload = payload;
    }

    static void validate(int version, int type, int payloadSize) throws IOException {
        if (version != VERSION || (type != TYPE_SPAKE2 && type != TYPE_PEER_INFO)
                || payloadSize <= 0 || payloadSize > MAX_FRAME_BYTES)
            throw new IOException("Invalid ADB pairing frame");
    }

    static OnDeviceAdbPairingFrame read(InputStream stream) throws IOException {
        byte[] header = new byte[HEADER_BYTES];
        readExact(stream, header);
        ByteBuffer view = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
        int version = view.get() & 0xff;
        int type = view.get() & 0xff;
        int length = view.getInt();
        validate(version, type, length);  // Never allocate from an untrusted length.
        byte[] payload = new byte[length];
        try {
            readExact(stream, payload);
            return new OnDeviceAdbPairingFrame(type, payload);
        } catch (IOException failure) {
            Arrays.fill(payload, (byte) 0);
            throw failure;
        }
    }

    static void write(OutputStream stream, int type, byte[] payload) throws IOException {
        if (payload == null) throw new IOException("Invalid ADB pairing frame");
        validate(VERSION, type, payload.length);
        ByteBuffer view = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);
        view.put((byte) VERSION);
        view.put((byte) type);
        view.putInt(payload.length);
        stream.write(view.array());
        stream.write(payload);
        stream.flush();
    }

    private static void readExact(InputStream stream, byte[] dest) throws IOException {
        int consumed = 0;
        while (consumed < dest.length) {
            int count = stream.read(dest, consumed, dest.length - consumed);
            if (count < 0) throw new EOFException("Truncated ADB pairing frame");
            if (count == 0) throw new IOException("No progress reading ADB pairing frame");
            consumed += count;
        }
    }

    @Override public void close() {
        Arrays.fill(payload, (byte) 0);
    }
}
