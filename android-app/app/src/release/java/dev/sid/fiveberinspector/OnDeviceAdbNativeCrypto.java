package dev.sid.fiveberinspector;

import java.security.GeneralSecurityException;

/** Native SPAKE2 + AES-GCM provider (pending APK native-library security gate). */
final class OnDeviceAdbNativeCrypto implements OnDeviceAdbPairingExchange.Crypto {
    private static final boolean AVAILABLE = loadNative();
    private static boolean loadNative() {
        try {System.loadLibrary("8bercue_adb_pairing");return true;}
        catch(UnsatisfiedLinkError unavailable){return false;}
    }
    static boolean available(){return AVAILABLE;}

    private long handle;
    private OnDeviceAdbNativeCrypto(long handle) {this.handle = handle;}

    static OnDeviceAdbPairingExchange.Crypto create(byte[] password) throws GeneralSecurityException {
        if (!AVAILABLE) throw new GeneralSecurityException("ADB pairing native provider unavailable");
        if (password == null || password.length != 70)
            throw new GeneralSecurityException("Invalid ADB pairing key material");
        long ptr = nativeCreate(password);
        if (ptr <= 0) throw new GeneralSecurityException("Unable to create ADB SPAKE2 session");
        return new OnDeviceAdbNativeCrypto(ptr);
    }
    private long requireActive() throws GeneralSecurityException {
        if (handle == 0) throw new GeneralSecurityException("ADB pairing session closed");
        return handle;
    }
    @Override public synchronized byte[] firstMessage() throws GeneralSecurityException {
        return nativeMessage(requireActive());
    }
    @Override public synchronized boolean acceptPeerMessage(byte[] data) throws GeneralSecurityException {
        return nativeAccept(requireActive(), data);
    }
    @Override public synchronized byte[] seal(byte[] clear) throws GeneralSecurityException {
        return nativeSeal(requireActive(),clear);
    }
    @Override public synchronized byte[] open(byte[] cipher) throws GeneralSecurityException {
        return nativeOpen(requireActive(),cipher);
    }
    @Override public synchronized void close() {
        if(handle!=0){long ptr=handle;handle=0;nativeDestroy(ptr);}
    }

    private static native long nativeCreate(byte[] password) throws GeneralSecurityException;
    private static native byte[] nativeMessage(long handle) throws GeneralSecurityException;
    private static native boolean nativeAccept(long handle,byte[] peer) throws GeneralSecurityException;
    private static native byte[] nativeSeal(long handle,byte[] data) throws GeneralSecurityException;
    private static native byte[] nativeOpen(long handle,byte[] data) throws GeneralSecurityException;
    private static native void nativeDestroy(long handle);
}
