package dev.sid.fiveberinspector;

import android.net.ssl.SSLEngines;
import android.os.Build;

import java.io.IOException;
import java.security.GeneralSecurityException;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;

/**
 * Public Android platform Conscrypt exporter for an app-owned TLS 1.3
 * SSLEngine. Never export from a different connection than the pairing PAKE.
 *
 * Android API 31+ only. Android 11/API 30 has no equivalent public exporter
 * and must fail closed until an independently reviewed provider is available.
 */
final class OnDeviceAdbTlsExporter {
    // The terminal NUL is specified by AOSP's pairing-connection exporter label.
    private static final String ADB_PAIRING_LABEL = "adb-label\u0000";
    private OnDeviceAdbTlsExporter() {}

    static byte[] export(SSLEngine engine) throws IOException, GeneralSecurityException {
        if (Build.VERSION.SDK_INT < 31)
            throw new GeneralSecurityException("ADB TLS exporter unavailable on this Android version");
        if (engine == null || !SSLEngines.isSupportedEngine(engine))
            throw new GeneralSecurityException("ADB pairing requires platform Conscrypt TLS engine");
        SSLSession session = engine.getSession();
        if (session == null || !"TLSv1.3".equals(session.getProtocol()))
            throw new SSLException("ADB pairing requires completed TLS 1.3 handshake");
        byte[] keyingMaterial = SSLEngines.exportKeyingMaterial(engine,ADB_PAIRING_LABEL,null,64);
        if (keyingMaterial == null || keyingMaterial.length != 64)
            throw new GeneralSecurityException("ADB TLS exporter returned invalid material");
        return keyingMaterial;
    }
}
