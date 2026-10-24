package dev.sid.fiveberinspector;

import android.content.Context;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import javax.net.ssl.SSLSocket;

/** Wireless TLS pairing and restricted authenticated management bootstrap. */
final class OnDeviceAdbWirelessManager {
    private OnDeviceAdbWirelessManager() {}

    static void pair(Context context, OnDeviceAdbDiscovery.Endpoint endpoint, char[] code)
            throws IOException, GeneralSecurityException {
        try {
            OnDeviceAdbIdentity id = OnDeviceAdbIdentity.getOrCreate(context);
            String guid=OnDeviceAdbPairingTransport.pair(endpoint,code,id);
            // Persist only after the TLS-bound SPAKE2 proof and AEAD verification.
            if(guid.isEmpty())throw new IOException("Paired device identity missing");
            if(!context.getSharedPreferences("wireless_adb_identity",Context.MODE_PRIVATE)
                    .edit().remove("serverFingerprint").putString("pairedGuid",guid).commit())
                throw new IOException("ADB pairing state not stored");
        } catch(IOException | GeneralSecurityException exception) {
            throw exception;
        } catch(Exception error) {
            throw new GeneralSecurityException("Wireless debugging identity unavailable",error);
        }
    }

    static boolean hasPaired(Context context) {
        return !context.getSharedPreferences("wireless_adb_identity",Context.MODE_PRIVATE)
                .getString("pairedGuid","").isEmpty();
    }

    static void startManagement(Context context, OnDeviceAdbDiscovery.Endpoint endpoint)
            throws IOException, GeneralSecurityException {
        if (endpoint == null || endpoint.purpose != OnDeviceAdbDiscovery.Purpose.CONNECTION)
            throw new IOException("Not an ADB connection service");
        InetAddress local=InetAddress.getByName(endpoint.address);
        if(!OnDeviceAdbDiscovery.isOwnAddress(local) || endpoint.port<=0 || endpoint.port>65535)
            throw new IOException("ADB connection endpoint is not local");
        if(CompanionBinderProvider.management()!=null) return;
        if(context.getSharedPreferences("wireless_adb_identity",Context.MODE_PRIVATE)
                .getString("pairedGuid","").isEmpty())
            throw new IOException("Wireless ADB pairing has not completed in this app");
        OnDeviceAdbIdentity identity;
        try {identity=OnDeviceAdbIdentity.getExisting(context);}
        catch(Exception failure){throw new GeneralSecurityException("Wireless debugging identity unavailable",failure);}
        OnDeviceAdbTlsContext tlsContext=OnDeviceAdbTlsContext.createManagement(identity);
        android.content.SharedPreferences pairPrefs=context.getSharedPreferences("wireless_adb_identity",Context.MODE_PRIVATE);
        try(Socket plain=new Socket()) {
            plain.connect(new InetSocketAddress(local,endpoint.port),5000);
            plain.setSoTimeout(18000);
            // The Binder challenge is the exact production explicit one-shot window.
            CompanionBinderProvider.beginManagementBootstrap();
            try {
              OnDeviceAdbRestrictedClient.run(plain.getInputStream(),plain.getOutputStream(),()->{
                SSLSocket tls=tlsContext.openOver(plain,local,endpoint.port);
                try {
                    tls.startHandshake();
                    OnDeviceAdbConnectionPolicy.verify(tls.getSession().getProtocol(),
                            tls.getSession().getPeerCertificates(),
                            tls.getSession().getLocalCertificates(),identity.publicKey);
                    android.util.Log.i("EightberAdbTls", "tls13_client_identity_presented");
                    if (pairPrefs.contains("serverFingerprint")) {
                        byte[] digest=java.security.MessageDigest.getInstance("SHA-256")
                                .digest(tls.getSession().getPeerCertificates()[0].getEncoded());
                        try {
                            String current=android.util.Base64.encodeToString(digest,android.util.Base64.NO_WRAP);
                            android.util.Log.i("EightberAdbTls", "legacy_der_changed="
                                    + !current.equals(pairPrefs.getString("serverFingerprint","")));
                        } finally {java.util.Arrays.fill(digest,(byte)0);}
                    }
                    tls.setSoTimeout(35000); // Connect's bounded 25-second Binder startup.
                    return new OnDeviceAdbRestrictedClient.AuthenticatedStream() {
                        @Override public java.io.InputStream input() throws IOException {return tls.getInputStream();}
                        @Override public java.io.OutputStream output() throws IOException {return tls.getOutputStream();}
                        // Presentation is not acceptance. The restricted client must
                        // read valid post-TLS CNXN before opening Connect.
                        @Override public boolean clientIdentityPresented() {
                            return true;
                        }
                        @Override public void close() throws IOException {tls.close();}
                    };
                } catch(Exception failure) {
                    try {tls.close();}catch(IOException ignored){}
                    if(failure instanceof IOException) throw (IOException)failure;
                    throw new IOException("Authenticated STLS handshake failed",failure);
                }
              },context.getApplicationInfo().sourceDir);
            } catch(IOException transportFailure) {
              // A stream can time out after the manager accepted our Binder.
              // Never claim success unless the live typed service really exists.
              if(CompanionBinderProvider.management()==null)throw transportFailure;
            }
        }
        if(CompanionBinderProvider.management()==null)
            throw new IOException("Command returned without authenticated management Binder");
        android.util.Log.i("EightberAdbTls", "authenticated_management_binder_ready");
        // Legacy DER pins are not AOSP identities. Migrate only after the normal
        // authenticated Binder handoff; retain the paired GUID and Keystore key.
        if(pairPrefs.contains("serverFingerprint")
                && !pairPrefs.edit().remove("serverFingerprint").commit())
            throw new IOException("Legacy wireless debugging pin migration failed");
    }
}
