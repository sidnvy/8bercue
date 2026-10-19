package dev.sid.fiveberinspector;

import android.os.IBinder;
import dalvik.system.PathClassLoader;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/*
 * 5ber read-only APDU bridge for lpac's stdio driver.
 *
 * Allowlist: standard GSMA ISD-R SELECT, ES10c GetEID,
 * ES10c GetProfilesInfo, opt-in exact GetEuiccInfo2, conditional GET RESPONSE, CLOSE.
 * No arbitrary APDU input, no profile switching/deletion/download.
 * Communications over stdin/stdout; never log data payloads.
 */
public final class NativeLpacReadOnlyBridge {
    private static final String AID = "A0000005591010FFFFFFFF8900000100";
    private static final int MAX_FRAMES = 24, MAX_BYTES = 32768;
    private final boolean allowCapabilities;
    // Explicit read-only card-centric selection; normal bridge still uses
    // device+EID-pinned discover() for all existing write-related callers.
    private final FiveberReaderSelector.Target knownCardForRead;
    private CardReadApduPolicy policy;
    public NativeLpacReadOnlyBridge(){this(false);}
    NativeLpacReadOnlyBridge(boolean allowCapabilities){
        this.allowCapabilities=allowCapabilities;this.knownCardForRead=null;
    }
    NativeLpacReadOnlyBridge(FiveberReaderSelector.Target knownCardForRead) {
        if (knownCardForRead == null || !FiveberReaderSelector.isKnownCardHash(knownCardForRead.identityHash))
            throw new SecurityException("Recognized card required for read-only bridge");
        this.allowCapabilities=false;
        this.knownCardForRead=knownCardForRead;
    }
    private Object hal;
    private Class<?> halType;
    private String selectedReader;
    private int channel = -1, frames = 0, totalBytes = 0;
    private boolean querySent = false, continuation = false;

    private static byte[] decode(String hex) {
        if (hex.length() % 2 != 0 || hex.length() > 1024)
            throw new IllegalArgumentException("invalid hex length");
        byte[] result = new byte[hex.length() / 2];
        for (int i = 0; i < result.length; i++)
            result[i] = (byte) Integer.parseInt(hex.substring(i*2, i*2+2), 16);
        return result;
    }

    private static String encode(byte[] bytes) {
        char[] alphabet = "0123456789ABCDEF".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            result[i*2] = alphabet[(bytes[i] >>> 4) & 15];
            result[i*2+1] = alphabet[bytes[i] & 15];
        }
        return new String(result);
    }

    private static JSONObject response(int code, String data) throws Exception {
        JSONObject payload = new JSONObject().put("ecode", code);
        if (data != null) payload.put("data", data);
        return new JSONObject().put("type", "apdu").put("payload", payload);
    }

    private void ensureConnected() throws Exception {
        if (hal != null) return;
        // Auto-enrolled EID pin wins; never guess the target by logical slot,
        // isRemovable() or the presence of an unrelated built-in eUICC.
        FiveberReaderSelector.Target target = knownCardForRead == null
            ? FiveberReaderSelector.discover()
            : FiveberReaderSelector.discoverKnownCardReadOnly();
        if (knownCardForRead != null &&
                (!knownCardForRead.reader.equals(target.reader)
                || !knownCardForRead.identityHash.equals(target.identityHash)))
            throw new SecurityException("Recognized card moved during read");
        selectedReader = target.reader;
        Class<?> manager = Class.forName("android.os.ServiceManager");
        Class<?> binderType = Class.forName("android.os.IBinder");
        Object binder = manager.getMethod("getService", String.class)
            .invoke(null, "android.hardware.secure_element.ISecureElement/"+selectedReader);
        if (binder == null) throw new IllegalStateException("Pinned 5ber HAL reader unavailable");
        ClassLoader loader = new PathClassLoader(
            SystemHalAdapter.dexPath(),
            NativeLpacReadOnlyBridge.class.getClassLoader());
        halType = Class.forName("android.hardware.secure_element.ISecureElement", true, loader);
        Class<?> stub = Class.forName("android.hardware.secure_element.ISecureElement$Stub", true, loader);
        hal = stub.getMethod("asInterface", binderType).invoke(null, binder);
        boolean present = (Boolean) halType.getMethod("isCardPresent").invoke(hal);
        if (!present) throw new IllegalStateException("Pinned 5ber HAL reader unavailable");
    }

    String selectedReader() {
        if (selectedReader == null) throw new IllegalStateException("5ber reader not pinned");
        return selectedReader;
    }

    void close() {
        if (channel > 0 && hal != null && halType != null) {
            try {
                halType.getMethod("closeChannel", byte.class).invoke(hal, (byte)channel);
                System.err.println("AUDIT channel_close=success");
            } catch (Throwable err) {
                System.err.println("SECURITY_WARNING channel_close_failed");
            }
        }
        channel = -1;
        policy = null;
        querySent = false;
        continuation = false;
    }

    private int open(String candidate) throws Exception {
        if (!AID.equals(candidate)) throw new SecurityException("AID not in allowlist");
        if (channel >= 0) throw new IllegalStateException("duplicate open");
        ensureConnected();
        Object openResult = halType.getMethod("openLogicalChannel", byte[].class, byte.class)
            .invoke(hal, decode(AID), (byte) 0);
        channel = openResult.getClass().getField("channelNumber").getInt(openResult);
        if (channel < 1 || channel > 3)
            throw new IllegalStateException("unexpected logical channel number");
        policy = new CardReadApduPolicy(channel,allowCapabilities);
        System.err.println("AUDIT channel_open=success");
        return channel;
    }

    private String transmit(String commandHex) throws Exception {
        if (channel < 1 || channel > 3) throw new IllegalStateException("channel not open");
        if (frames >= MAX_FRAMES) throw new IllegalStateException("request budget reached");
        byte[] apdu = decode(commandHex);
        if (apdu.length < 5 || (apdu[0] & 255) != (0x80 | channel))
            throw new SecurityException("invalid APDU or logical channel");

        // Default native engine remains EID/profile-only. Capability callers explicitly opt in.
        policy.authorize(apdu);
        byte[] result = (byte[]) halType.getMethod("transmit", byte[].class)
            .invoke(hal, (Object) apdu);
        if (result == null || result.length < 2 || result.length > 8192)
            throw new IllegalStateException("invalid HAL response length");
        policy.acceptResponse(result);
        frames++;
        totalBytes += result.length - 2;
        if (totalBytes > MAX_BYTES) throw new IllegalStateException("response budget reached");
        int sw1 = result[result.length-2] & 255;
        continuation = (sw1 == 0x61);
        System.err.println("AUDIT response_frame=" + frames + " body_bytes=" + (result.length-2)
            + " continuation=" + continuation);
        return encode(result);
    }

    JSONObject handle(JSONObject request) throws Exception {
        if (!"apdu".equals(request.getString("type")))
            throw new IllegalArgumentException("expected lpac APDU request");
        JSONObject payload = request.getJSONObject("payload");
        String fn = payload.getString("func");
        String param = payload.isNull("param") ? "" : payload.optString("param", "").toUpperCase();
        if (fn.equals("connect")) {
            ensureConnected();
            return response(0, null);
        }
        if (fn.equals("disconnect") || fn.equals("logic_channel_close")) {
            close();
            return response(0, null);
        }
        if (fn.equals("logic_channel_open")) return response(open(param), null);
        if (fn.equals("transmit")) return response(0, transmit(param));
        throw new SecurityException("operation not in allowlist");
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !args[0].equals("--read-only"))
            throw new IllegalArgumentException("Requires --read-only");
        NativeLpacReadOnlyBridge bridge = new NativeLpacReadOnlyBridge();
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
        PrintWriter output = new PrintWriter(System.out, true);
        try {
            String line;
            while ((line = input.readLine()) != null) {
                JSONObject result;
                try {
                    result = bridge.handle(new JSONObject(line));
                } catch (Throwable error) {
                    Throwable cause = error;
                    if (cause instanceof InvocationTargetException
                      && ((InvocationTargetException) cause).getTargetException() != null)
                        cause = ((InvocationTargetException) cause).getTargetException();
                    System.err.println("AUDIT request_failed=" + cause.getClass().getSimpleName());
                    result = response(-1, null);
                }
                output.println(result.toString());
            }
        } finally {
            bridge.close();
            System.err.println("AUDIT bridge_exit");
        }
    }
}
