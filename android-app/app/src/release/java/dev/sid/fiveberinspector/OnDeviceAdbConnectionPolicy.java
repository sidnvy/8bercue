package dev.sid.fiveberinspector;

import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

/** AOSP has no durable server identity. This policy validates TLS shape, not trust.
 * Authority comes from the local endpoint, paired client key, post-TLS CNXN and
 * the independent Shell UID / nonce / APK / typed Binder checks. See security review.
 */
final class OnDeviceAdbConnectionPolicy {
    private OnDeviceAdbConnectionPolicy() {}

    static void checkServerCertificate(X509Certificate[] chain) throws CertificateException {
        if (chain == null || chain.length != 1 || chain[0] == null)
            throw new CertificateException("Expected single self-signed ADB certificate");
        X509Certificate cert = chain[0];
        if (!cert.getSubjectX500Principal().equals(cert.getIssuerX500Principal()))
            throw new CertificateException("ADB certificate is not self-issued");
        try { cert.verify(cert.getPublicKey()); }
        catch (Exception invalid) { throw new CertificateException("ADB certificate signature invalid",invalid); }
        // A self-signature proves key possession only, never remote authorization.
        // AOSP regenerates dates on every connection; do not pin DER or dates.
    }

    static void verify(String protocol, Certificate[] peer, Certificate[] local, PublicKey expected)
            throws CertificateException {
        if (!"TLSv1.3".equals(protocol)) throw new CertificateException("TLS 1.3 required");
        if (peer == null || peer.length != 1 || !(peer[0] instanceof X509Certificate))
            throw new CertificateException("Missing ADB server certificate");
        checkServerCertificate(new X509Certificate[]{(X509Certificate)peer[0]});
        if (expected == null || local == null || local.length != 1 || local[0] == null
                || !java.util.Arrays.equals(expected.getEncoded(),local[0].getPublicKey().getEncoded()))
            throw new CertificateException("Paired client identity not presented");
    }
}
