package dev.sid.fiveberinspector;

import java.io.ByteArrayInputStream;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/** Shared by production ES9 transport and hermetic TLS handshakes. No permissive verifier. */
final class VerifiedEs9Tls {
    private static final String ROOT_DER_SHA256=
        "5e3e91fd454327c3af5d32a7a73bbc59fe43aa7d85fd32d5db44423f80a56bb3";
    static SSLSocketFactory pinnedFactory(byte[] pem) throws Exception {
        if(pem==null || pem.length<250 || pem.length>2048)
            throw new SecurityException("Invalid GSMA trust anchor length");
        X509Certificate root=(X509Certificate)CertificateFactory.getInstance("X.509")
            .generateCertificate(new ByteArrayInputStream(pem));
        StringBuilder hex=new StringBuilder();
        for(byte b:MessageDigest.getInstance("SHA-256").digest(root.getEncoded()))
            hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        if(!ROOT_DER_SHA256.equals(hex.toString()))
            throw new SecurityException("GSMA RSP2 root fingerprint does not match trusted source");
        return contextForRoot(root).getSocketFactory();
    }
    // Internal PKIX construction. Production reaches it only through the pinned root check.
    // Tests supply an ephemeral CA because no test possesses a GSMA CI private key.
    static SSLContext contextForRoot(X509Certificate root) throws Exception {
        root.checkValidity();
        KeyStore store=KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null,null);
        store.setCertificateEntry("gsma-live-rsp2-ci1",root);
        TrustManagerFactory trust=TrustManagerFactory.getInstance("PKIX");
        trust.init(store);
        SSLContext context=SSLContext.getInstance("TLS");
        context.init(null,trust.getTrustManagers(),new SecureRandom());
        return context;
    }
    static void configure(HttpsURLConnection connection,SSLSocketFactory factory) {
        connection.setSSLSocketFactory(factory);
        // Preserve the platform hostname verifier. Never install a permissive override.
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(30000);
        connection.setUseCaches(false);
    }
    private VerifiedEs9Tls(){}
}
