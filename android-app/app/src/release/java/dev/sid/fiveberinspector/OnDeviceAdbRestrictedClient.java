package dev.sid.fiveberinspector;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Fixed-entrypoint ADB transport state machine. Does not implement TLS crypto.
 * It must be supplied a mutually authenticated STLS upgrader bound to the
 * app's previously paired certificate. No classic RSA fallback, no arbitrary
 * shell services, no ADB daemon lifecycle control, no profile write operations.
 */
final class OnDeviceAdbRestrictedClient {
    private static final int STREAM_ID = 1;
    private static final int MAX_PACKETS = 512;
    private static final int MAX_RESPONSE_BYTES = 128 * 1024;
    private static final byte[] EMPTY = new byte[0];

    interface AuthenticatedStream extends AutoCloseable {
        InputStream input() throws IOException;
        OutputStream output() throws IOException;
        /** Must validate the previously paired host certificate, not trust all. */
        boolean verifiedPairedIdentity();
        @Override void close() throws IOException;
    }

    interface TlsUpgrade {
        /** TLS handshake and peer-identity validation run on this SAME wire. */
        AuthenticatedStream upgrade() throws IOException;
    }

    private OnDeviceAdbRestrictedClient() {}

    static String fixedEntrypoint(String installedApk) throws IOException {
        if (installedApk == null || installedApk.length() > 256
                || !installedApk.matches("/data/app/[A-Za-z0-9_~./+=-]+/base\\.apk"))
            throw new IOException("Invalid installed application source");
        return "shell:CLASSPATH=" + installedApk + " /system/bin/app_process / "
                + "dev.sid.fiveberinspector.Connect\u0000";
    }

    /**
     * The return value says only that a restricted command stream ended without
     * transport error. The caller MUST separately verify the authenticated
     * management Binder before declaring eUICC management ready.
     */
    static void run(InputStream plainInput, OutputStream plainOutput,
                    TlsUpgrade upgrade, String installedApk) throws IOException {
        byte[] command = fixedEntrypoint(installedApk).getBytes(StandardCharsets.US_ASCII);
        if (command.length > 512) throw new IOException("Restricted command oversized");
        byte[] hello = "host::\u0000".getBytes(StandardCharsets.US_ASCII);
        OnDeviceAdbWirePacket.write(plainOutput, OnDeviceAdbWirePacket.A_CNXN,
                OnDeviceAdbWirePacket.A_VERSION, 4096, hello);
        try (OnDeviceAdbWirePacket start = OnDeviceAdbWirePacket.read(plainInput)) {
            // Android 11+ TLS-only path. An A_AUTH reply indicates classic RSA;
            // rejecting it prevents accidental downgrade.
            if (start.command != OnDeviceAdbWirePacket.A_STLS
                    || start.arg0 != OnDeviceAdbWirePacket.A_STLS_VERSION
                    || start.payload.length != 0)
                throw new IOException("ADB TLS required; insecure/unsupported handshake rejected");
        }
        OnDeviceAdbWirePacket.write(plainOutput,OnDeviceAdbWirePacket.A_STLS,
                OnDeviceAdbWirePacket.A_STLS_VERSION,0,EMPTY);

        try (AuthenticatedStream tls = upgrade.upgrade()) {
            if (!tls.verifiedPairedIdentity())
                throw new IOException("ADB paired device certificate not verified");
            InputStream in=tls.input();OutputStream out=tls.output();
            int negotiatedVersion;
            // The first post-TLS CNXN can use checksum=0 if the server supports
            // Android ADB v1.1. The peer is already mutually TLS-authenticated.
            try (OnDeviceAdbWirePacket connected = OnDeviceAdbWirePacket.read(in, true)) {
                if (connected.command != OnDeviceAdbWirePacket.A_CNXN
                        || connected.arg0 < OnDeviceAdbWirePacket.A_VERSION_MIN
                        || connected.arg0 > OnDeviceAdbWirePacket.A_VERSION)
                    throw new IOException("TLS ADB connection not established");
                negotiatedVersion = Math.min(connected.arg0, OnDeviceAdbWirePacket.A_VERSION);
            }
            boolean skipChecksum = negotiatedVersion >= OnDeviceAdbWirePacket.A_VERSION_SKIP_CHECKSUM;
            OnDeviceAdbWirePacket.write(out,OnDeviceAdbWirePacket.A_OPEN,STREAM_ID,0,command,skipChecksum);
            int remoteId = -1;
            int accumulated = 0;
            for (int n=0;n<MAX_PACKETS;n++) {
                try (OnDeviceAdbWirePacket reply = OnDeviceAdbWirePacket.read(in,skipChecksum)) {
                    if (reply.command == OnDeviceAdbWirePacket.A_OKAY) {
                        if (reply.arg1 != STREAM_ID || reply.arg0 <= 0
                                || (remoteId != -1 && remoteId != reply.arg0))
                            throw new IOException("ADB stream identity mismatch");
                        remoteId=reply.arg0;
                    } else if (reply.command == OnDeviceAdbWirePacket.A_WRTE) {
                        if (remoteId <= 0 || reply.arg0 != remoteId || reply.arg1 != STREAM_ID)
                            throw new IOException("ADB stream identity mismatch");
                        accumulated += reply.payload.length;
                        if (accumulated > MAX_RESPONSE_BYTES)
                            throw new IOException("Restricted ADB command output exceeded limit");
                        // Ack but do NOT log command output (it may contain identifiers).
                        OnDeviceAdbWirePacket.write(out,OnDeviceAdbWirePacket.A_OKAY,
                                STREAM_ID, remoteId, EMPTY, skipChecksum);
                    } else if (reply.command == OnDeviceAdbWirePacket.A_CLSE) {
                        if (remoteId <= 0 || reply.arg0 != remoteId || reply.arg1 != STREAM_ID)
                            throw new IOException("ADB close identity mismatch");
                        OnDeviceAdbWirePacket.write(out,OnDeviceAdbWirePacket.A_CLSE,
                                STREAM_ID,remoteId,EMPTY,skipChecksum);
                        return;
                    } else throw new IOException("Unexpected ADB stream packet");
                }
            }
            throw new IOException("ADB session exceeded packet budget");
        }
    }
}
