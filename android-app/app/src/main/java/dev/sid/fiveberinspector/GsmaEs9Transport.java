package dev.sid.fiveberinspector;

import android.os.Process;
import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.net.ssl.HttpsURLConnection;

/**
 * A narrowly-scoped verified eSIM ES9+ HTTPS transport.
 *
 * This is NOT the upstream lpac curl driver: that driver switches TLS
 * certificate and hostname validation off. This transport explicitly trusts
 * the GSMA live RSP2 CI1 root, pins its DER SHA256, and retains the standard
 * HTTPS hostname verifier. Never accepts HTTP/redirects/proxy override.
 *
 * Generic downloads bind an approved host for one guarded transaction.
 * tlsProbe() performs an anonymous GET against the original experimental host.
 */
public final class GsmaEs9Transport {
    private static final String ROOT_APK_ASSET = "assets/fiveber-certs/gsma-rsp2-root-ci1.pem";
    private static final int MAX_TX_BYTES = 1024 * 1024;
    private static final int MAX_RX_BYTES = 4 * 1024 * 1024;
    private static final String PATH_PREFIX = "/gsma/rsp2/es9plus/";
    private final javax.net.ssl.SSLSocketFactory tls;
    private final String approvedHost;

    public static final class Reply {
        public final int statusCode;
        public final byte[] data;
        Reply(int code, byte[] bytes) {
            this.statusCode = code;
            this.data = bytes;
        }
    }

    private static String localApk() throws Exception {
        return NativeLpacBundle.installedApk();
    }


    public GsmaEs9Transport(String host) throws Exception {
        approvedHost=ActivationCode.validateHost(host);
        if (Process.myUid() != Process.SHELL_UID) {
            throw new SecurityException("GSMA eSIM transport requires Shell Companion");
        }
        byte[] pem;
        try (ZipFile apk = new ZipFile(localApk())) {
            ZipEntry cert = apk.getEntry(ROOT_APK_ASSET);
            if (cert == null || cert.getSize() < 250 || cert.getSize() > 2048) {
                throw new SecurityException("Missing GSMA trust anchor");
            }
            try (InputStream in = apk.getInputStream(cert);
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[1024];
                int n;
                while ((n = in.read(chunk)) != -1) {
                    if (out.size() + n > 2048) throw new SecurityException("GSMA root too large");
                    out.write(chunk, 0, n);
                }
                pem = out.toByteArray();
            }
        }
        tls = VerifiedEs9Tls.pinnedFactory(pem);
    }

    URI validateTransactionEndpoint(String raw) throws Exception {
        return ActivationCode.endpoint(raw,approvedHost);
    }

    public Reply tlsProbe() throws Exception {
        // No user information, activation code, challenge, EID or APDU.
        URI url = validateTransactionEndpoint("https://" + approvedHost + PATH_PREFIX + "initiateAuthentication");
        HttpsURLConnection connection = (HttpsURLConnection) url.toURL().openConnection();
        try {
            VerifiedEs9Tls.configure(connection,tls);
            connection.setRequestMethod("GET");
            int status = connection.getResponseCode();
            if (status < 200 || status > 599) throw new SecurityException("Invalid HTTP response code");
            return new Reply(status, new byte[0]);
        } finally {
            connection.disconnect();
        }
    }

    private static void validateHeaders(JSONArray headers) throws Exception {
        if (headers.length() > 5) throw new SecurityException("Too many HTTP headers");
        for (int i = 0; i < headers.length(); i++) {
            String s = headers.getString(i);
            if (s == null || s.length() > 140 || s.contains("\r") || s.contains("\n"))
                throw new SecurityException("Unsafe HTTP header");
            int colon = s.indexOf(':');
            if (colon <= 0) throw new SecurityException("Malformed HTTP header");
            String key = s.substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT);
            String value = s.substring(colon + 1).trim();
            boolean accepted =
                   ("user-agent".equals(key) && "gsma-rsp-lpad".equals(value))
                || ("x-admin-protocol".equals(key) && "gsma/rsp/v2.2.2".equals(value))
                || ("content-type".equals(key) && "application/json".equalsIgnoreCase(value));
            if (!accepted) throw new SecurityException("Unapproved HTTP request header");
        }
    }

    public Reply postEs9(String rawUrl, byte[] body, JSONArray headers) throws Exception {
        URI url = validateTransactionEndpoint(rawUrl);
        if (body == null || body.length < 2 || body.length > MAX_TX_BYTES
            || body[0] != '{' || body[body.length - 1] != '}') {
            throw new SecurityException("Invalid bounded JSON ES9+ request");
        }
        validateHeaders(headers);
        HttpsURLConnection connection = (HttpsURLConnection) url.toURL().openConnection();
        try {
            VerifiedEs9Tls.configure(connection,tls);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("User-Agent", "gsma-rsp-lpad");
            connection.setRequestProperty("X-Admin-Protocol", "gsma/rsp/v2.2.2");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                // Never follow redirects; discard server-supplied error payload
                // to avoid leaking arbitrary authentication details to the App.
                return new Reply(code, new byte[0]);
            }
            try (InputStream in = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) != -1) {
                    if (output.size() + n > MAX_RX_BYTES) {
                        throw new SecurityException("Oversized SM-DP+ server reply");
                    }
                    output.write(chunk, 0, n);
                }
                return new Reply(code, output.toByteArray());
            }
        } finally {
            connection.disconnect();
        }
    }
}
