package dev.sid.fiveberinspector;

import java.io.IOException;
import java.net.InetAddress;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Objects;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;

/**
 * Secure connection boundary for pairing to the phone's own DNS-SD endpoint.
 *
 * TLS socket construction and exporter derivation MUST come from a separately
 * reviewed provider with an app-specific, persistent client identity. A mock
 * provider is suitable for contract tests only and MUST NOT be packaged for use
 * in a production pairing path. No TLS certificate is trusted by this class;
 * SPAKE2/AEAD verification is mandatory before any successful pairing result.
 */
final class OnDeviceAdbPairingTransport {
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 12000;

    interface TlsSocketFactory {
        /** Must create an app-identity TLS socket to the supplied local IP/port. */
        SSLSocket open(InetAddress localAddress, int port, int timeoutMillis) throws IOException;
    }

    interface Exporter {
        /** Return 64 bytes of exporter output from THIS completed TLS session. */
        byte[] export(SSLSocket socket) throws GeneralSecurityException;
    }

    /** Real Android API 31+ pairing implementation; peer is verified via SPAKE2. */
    static String pair(OnDeviceAdbDiscovery.Endpoint endpoint, char[] code,
                     OnDeviceAdbIdentity identity) throws IOException, GeneralSecurityException {
        OnDeviceAdbTlsContext tls = OnDeviceAdbTlsContext.create(identity);
        return pair(endpoint, code, identity.pairingPublicKey(), tls::open,
                OnDeviceAdbTlsContext::export, OnDeviceAdbNativeCrypto::create);
    }

    private OnDeviceAdbPairingTransport() {}

    static String pair(OnDeviceAdbDiscovery.Endpoint endpoint, char[] code, byte[] publicKey,
                     TlsSocketFactory sockets, Exporter exporter,
                     OnDeviceAdbPairingExchange.CryptoFactory crypto)
            throws IOException, GeneralSecurityException {
        InetAddress local;
        try {
            Objects.requireNonNull(endpoint);
            Objects.requireNonNull(sockets);
            Objects.requireNonNull(exporter);
            Objects.requireNonNull(crypto);
            // Never resolve DNS names or remotely advertised service addresses.
            if (endpoint.purpose != OnDeviceAdbDiscovery.Purpose.PAIRING
                    || endpoint.port <= 0 || endpoint.port > 65535
                    || !endpoint.address.matches("[0-9a-fA-F:.%]+"))
                throw new IOException("Untrusted pairing endpoint");
            local = InetAddress.getByName(endpoint.address);
            if (!OnDeviceAdbDiscovery.isOwnAddress(local))
                throw new IOException("Pairing endpoint is not on this device");
            if (!OnDeviceAdbPairingExchange.validCode(code))
                throw new IOException("Invalid ADB pairing code");

        } catch (IOException | RuntimeException invalid) {
            // Code is short-lived even when the endpoint is rejected before TLS.
            if (code != null) Arrays.fill(code, '\0');
            throw invalid;
        }
        byte[] exported = null;
        try (SSLSocket socket = sockets.open(local, endpoint.port, CONNECT_TIMEOUT_MS)) {
            socket.setSoTimeout(READ_TIMEOUT_MS);
            socket.setEnabledProtocols(new String[]{"TLSv1.3"});
            socket.startHandshake();
            SSLSession session = socket.getSession();
            if (session == null || !"TLSv1.3".equals(session.getProtocol()))
                throw new SSLException("ADB pairing requires TLS 1.3");
            exported = exporter.export(socket);
            if (exported == null || exported.length != OnDeviceAdbPairingExchange.TLS_EXPORTER_LENGTH)
                throw new SSLException("Invalid ADB pairing TLS exporter");
            return OnDeviceAdbPairingExchange.exchange(socket.getInputStream(),socket.getOutputStream(),
                    code,exported,publicKey,crypto);
        } finally {
            if (exported != null) Arrays.fill(exported,(byte) 0);
            if (code != null) Arrays.fill(code,'\0');
        }
    }
}
