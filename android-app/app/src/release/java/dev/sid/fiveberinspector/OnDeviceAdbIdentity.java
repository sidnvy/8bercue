package dev.sid.fiveberinspector;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.Calendar;
import java.util.Date;

import javax.security.auth.x500.X500Principal;

/**
 * Dedicated persistent non-exportable Android Keystore identity for app-owned
 * ADB wireless pairing. Deliberately separate from the legacy classic ADB key.
 *
 * Never rotate an existing mismatched key automatically: rotation means the
 * old Android wireless debugging pairing no longer authenticates.
 */
final class OnDeviceAdbIdentity {
    private static final String KEY_ALIAS = "dev.sid.fiveberinspector.adb_tls_v1";
    final PrivateKey privateKey;
    final X509Certificate certificate;
    final RSAPublicKey publicKey;

    private OnDeviceAdbIdentity(PrivateKey privateKey, X509Certificate cert,
                                RSAPublicKey pub) {
        this.privateKey = privateKey;
        this.certificate = cert;
        this.publicKey = pub;
    }

    static OnDeviceAdbIdentity getOrCreate(Context context) throws Exception {
        // Keystore keys are scoped to the app UID, not accessible from the Shell.
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(KEY_ALIAS)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
            Calendar years = Calendar.getInstance();
            years.add(Calendar.YEAR, 10);
            KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256,KeyProperties.DIGEST_SHA384,
                            KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,
                            KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                    .setCertificateSubject(new X500Principal("CN=8bercue Local ADB"))
                    .setCertificateSerialNumber(BigInteger.valueOf(1))
                    .setCertificateNotBefore(new Date(0))
                    .setCertificateNotAfter(years.getTime())
                    .build();
            generator.initialize(spec);
            KeyPair pair = generator.generateKeyPair();
            if (!(pair.getPublic() instanceof RSAPublicKey))
                throw new IOException("ADB keystore generated invalid key type");
        }
        KeyStore.Entry entry = store.getEntry(KEY_ALIAS, null);
        if (!(entry instanceof KeyStore.PrivateKeyEntry))
            throw new IOException("ADB pairing identity damaged; manual re-pair required");
        KeyStore.PrivateKeyEntry privateEntry = (KeyStore.PrivateKeyEntry)entry;
        Certificate cert = privateEntry.getCertificate();
        if (!(cert instanceof X509Certificate)
                || !(cert.getPublicKey() instanceof RSAPublicKey)
                || !"RSA".equalsIgnoreCase(privateEntry.getPrivateKey().getAlgorithm()))
            throw new IOException("ADB pairing identity invalid; manual re-pair required");
        RSAPublicKey pub = (RSAPublicKey)cert.getPublicKey();
        // Validates 2048-bit RSA Montgomery encoding before any use.
        OnDeviceAdbPublicKey.binary(pub);
        return new OnDeviceAdbIdentity(privateEntry.getPrivateKey(), (X509Certificate)cert, pub);
    }

    byte[] pairingPublicKey() throws IOException {return OnDeviceAdbPublicKey.pairInfo(publicKey);}
}
