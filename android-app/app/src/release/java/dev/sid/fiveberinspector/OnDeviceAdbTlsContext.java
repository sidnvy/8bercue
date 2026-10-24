package dev.sid.fiveberinspector;

import android.net.ssl.SSLSockets;
import android.os.Build;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.*;

/** TLS 1.3 identity for one-device local pairing. TLS trust bootstraps via SPAKE2. */
@android.annotation.TargetApi(31)
final class OnDeviceAdbTlsContext {
    private static final String IDENTITY_ALIAS = "8bercue-paired-adb";
    private final SSLContext tls;

    private OnDeviceAdbTlsContext(SSLContext context) {tls=context;}

    static OnDeviceAdbTlsContext create(OnDeviceAdbIdentity id) throws GeneralSecurityException {
        if (Build.VERSION.SDK_INT < 31) throw new GeneralSecurityException("Public TLS exporter unavailable");
        X509ExtendedKeyManager km = new X509ExtendedKeyManager() {
            @Override public String chooseClientAlias(String[] types,Principal[] issuers,Socket socket) {
                for(String type:types) if("RSA".equalsIgnoreCase(type))return IDENTITY_ALIAS;
                return null;
            }
            @Override public String chooseEngineClientAlias(String[] types,Principal[] issuers,SSLEngine engine) {
                return chooseClientAlias(types,issuers,null);
            }
            @Override public X509Certificate[] getCertificateChain(String alias) {
                return IDENTITY_ALIAS.equals(alias)?new X509Certificate[]{id.certificate}:null;
            }
            @Override public PrivateKey getPrivateKey(String alias) {
                return IDENTITY_ALIAS.equals(alias)?id.privateKey:null;
            }
            @Override public String[] getClientAliases(String type,Principal[] issuers) {
                return "RSA".equalsIgnoreCase(type)?new String[]{IDENTITY_ALIAS}:null;
            }
            @Override public String[] getServerAliases(String type,Principal[] issuers) {
                return getClientAliases(type,issuers);
            }
            @Override public String chooseServerAlias(String type,Principal[] issuers,Socket socket) {
                return "RSA".equalsIgnoreCase(type)?IDENTITY_ALIAS:null;
            }
            @Override public String chooseEngineServerAlias(String type,Principal[] issuers,SSLEngine engine) {
                return chooseServerAlias(type,issuers,null);
            }
        };
        // Pairing protocol is authenticated *after* TLS using password-authenticated
        // SPAKE2 plus TLS exporter. Do not interpret this pairing-only provisional
        // trust manager as verification of the remote cert identity.
        X509TrustManager pairingProvisionalTrust = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain,String authType) throws CertificateException {
                throw new CertificateException("Client certificate validation not applicable");
            }
            @Override public void checkServerTrusted(X509Certificate[] chain,String authType) throws CertificateException {
                if(chain==null||chain.length!=1||chain[0]==null)
                    throw new CertificateException("Unusable pairing server certificate");
                // The certificate is provisionally accepted; binding is via SPAKE2.
            }
            @Override public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}
        };
        SSLContext context=SSLContext.getInstance("TLSv1.3");
        context.init(new KeyManager[]{km},new TrustManager[]{pairingProvisionalTrust},new java.security.SecureRandom());
        return new OnDeviceAdbTlsContext(context);
    }

    SSLSocket open(InetAddress address,int port,int timeoutMillis) throws IOException {
        if(!OnDeviceAdbDiscovery.isOwnAddress(address) || port<1 || port>65535)
            throw new IOException("Non-local ADB endpoint");
        Socket raw=new Socket();
        try {
            raw.connect(new InetSocketAddress(address,port),timeoutMillis);
            SSLSocket socket=(SSLSocket)tls.getSocketFactory().createSocket(raw,address.getHostAddress(),port,true);
            if(!SSLSockets.isSupportedSocket(socket))throw new SSLException("Unsupported platform TLS socket");
            socket.setUseClientMode(true);
            socket.setEnabledProtocols(new String[]{"TLSv1.3"});
            socket.setSoTimeout(12000);
            return socket;
        } catch(IOException | RuntimeException failure) {
            try{raw.close();}catch(IOException ignored){}
            throw failure;
        }
    }

    SSLSocket openOver(Socket existing,InetAddress address,int port) throws IOException {
        if(!OnDeviceAdbDiscovery.isOwnAddress(address))throw new IOException("ADB connection host is not local");
        SSLSocket socket=(SSLSocket)tls.getSocketFactory().createSocket(
                existing,address.getHostAddress(),port,false);
        socket.setUseClientMode(true);
        socket.setEnabledProtocols(new String[]{"TLSv1.3"});
        socket.setSoTimeout(15000);
        return socket;
    }

    /** Synthetic loopback TLS 1.3 / Keystore / exporter check. Never touches adbd. */
    static boolean syntheticTlsSelfTest(OnDeviceAdbIdentity id) throws Exception {
        OnDeviceAdbTlsContext context=create(id);
        java.net.InetAddress loopback=java.net.InetAddress.getByName("127.0.0.1");
        try(javax.net.ssl.SSLServerSocket server=(javax.net.ssl.SSLServerSocket)
                  context.tls.getServerSocketFactory().createServerSocket(0,1,loopback)) {
            server.setEnabledProtocols(new String[]{"TLSv1.3"});
            server.setSoTimeout(8000);
            java.util.concurrent.ExecutorService executor=java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                java.util.concurrent.Future<byte[]> response=executor.submit(()->{
                    try(SSLSocket accepted=(SSLSocket)server.accept()) {
                        accepted.setEnabledProtocols(new String[]{"TLSv1.3"});
                        accepted.setSoTimeout(8000);
                        accepted.setUseClientMode(false);
                        accepted.startHandshake();
                        return export(accepted);
                    }
                });
                byte[] client=null,serverBytes=null;
                try(SSLSocket socket=context.open(loopback,server.getLocalPort(),4000)) {
                    socket.startHandshake();
                    client=export(socket);
                    serverBytes=response.get(10,java.util.concurrent.TimeUnit.SECONDS);
                    return java.security.MessageDigest.isEqual(client,serverBytes);
                } finally {
                    if(client!=null)java.util.Arrays.fill(client,(byte)0);
                    if(serverBytes!=null)java.util.Arrays.fill(serverBytes,(byte)0);
                }
            } finally {executor.shutdownNow();}
        }
    }

    static byte[] export(SSLSocket socket) throws GeneralSecurityException {
        if(Build.VERSION.SDK_INT<31 || !SSLSockets.isSupportedSocket(socket))
            throw new GeneralSecurityException("Android TLS exporter unavailable");
        if(!"TLSv1.3".equals(socket.getSession().getProtocol()))
            throw new GeneralSecurityException("TLS 1.3 not negotiated");
        try {
            byte[] material=SSLSockets.exportKeyingMaterial(socket,"adb-label\u0000",null,64);
            if(material==null||material.length!=64)
                throw new GeneralSecurityException("Invalid ADB TLS exporter size");
            return material;
        } catch(SSLException exception) {
            throw new GeneralSecurityException("TLS exporter failed",exception);
        }
    }
}
