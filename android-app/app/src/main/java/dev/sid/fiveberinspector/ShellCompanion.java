package dev.sid.fiveberinspector;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Process;
import android.net.Credentials;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * A small standalone Shell process started by the user's ADB shell.
 * Uses only local Unix domain sockets; no TCP port, no Internet permission.
 * Ensures the connecting app has the expected Android package UID.
 *
 * Protocol is STATUS, PROFILES, NATIVE_PROFILES, or anonymous TLS_PROBE.
 * DOWNLOAD_SPEEDTEST is the sole explicit-consent mutation action.
 * No arbitrary command/APDU or caller-supplied HTTP requests.
 */
public final class ShellCompanion {
    private static JSONObject lockedProfileRead() throws Exception {
        CardTransactionLock.acquire();
        try { return PhoneNativeLpac.listProfiles(); }
        finally { CardTransactionLock.release(); }
    }

    static final String SOCKET = "fiveber_inspector_readonly_v1";

    private ShellCompanion() {}

    public static void main(String[] args) throws Exception {
        if (Process.myUid() != Process.SHELL_UID)
            throw new SecurityException("This helper requires UID 2000 (ADB shell)");
        if (args.length != 2 || !"--app-uid".equals(args[0]))
            throw new IllegalArgumentException("Expected --app-uid <installed-package-uid>");
        final int appUid = Integer.parseInt(args[1]);
        if (appUid < 10000 || appUid > 199999)
            throw new IllegalArgumentException("Invalid Android app UID");

        // In production use, appUid is resolved from the installed package by
        // the user-authorized bootstrap script, not sent from the Android UI.
        System.err.println("5ber helper started UID=" + Process.myUid() + " allowing app UID=" + appUid);
        LocalServerSocket server = new LocalServerSocket(SOCKET);
        ProfileUserService handler = new ProfileUserService();
        try {
            while (true) {
                try (LocalSocket connection = server.accept()) {
                    connection.setSoTimeout(30000);
                    Credentials peer = connection.getPeerCredentials();
                    if (peer == null || peer.getUid() != appUid) {
                        System.err.println("5ber helper refused unauthorized peer");
                        continue;
                    }
                    // Requests are authenticated by kernel SO_PEERCRED AND
                    // rechecked against the currently installed package UID.
                    // Bind the verified identity to this one socket request
                    // so the existing Shizuku service's transaction logic can
                    // be reused without weakening its Binder caller checks.
                    try (ServiceCaller.VerifiedSocketCall verified =
                            ServiceCaller.enterVerifiedSocketCall(peer.getUid(), appUid)) {
                    ServiceCaller.requireApp();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(
                        connection.getInputStream(), StandardCharsets.UTF_8));
                    BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                        connection.getOutputStream(), StandardCharsets.UTF_8));
                    String request = CompanionProfileRpc.readBoundedLine(reader);
                    connection.setSoTimeout(LabSupport.requestTimeout(request));
                    JSONObject response = new JSONObject();
                    try {
                        if ("CAPABILITIES".equals(request)) {
                            response.put("ok", true);
                            response.put("result", CompanionProfileRpc.capabilities());
                        } else if (request.startsWith("{")) {
                            // Only the explicit, typed, bounded, journaled
                            // methods in CompanionProfileRpc are reachable.
                            response = CompanionProfileRpc.execute(
                                handler, new JSONObject(request));
                        } else if ("STATUS".equals(request)) {
                            response.put("ok", true);
                            response.put("status", handler.getStatus());
                        } else if ("PROFILES".equals(request)) {
                            // Portable read-only native libeuicc engine uses card EID
                            // instead of assuming Pixel SIM1; no write path exposed.
                            NativeLpacBundle.ensureInstalled();
                            response.put("ok", true);
                            response.put("result", lockedProfileRead());
                        } else if ("NATIVE_PROFILES".equals(request)) {
                            // Native components are installed from this signed APK only.
                            // Do not accept a binary path or any APDU input from the app.
                            NativeLpacBundle.ensureInstalled();
                            response.put("ok", true);
                            response.put("result", lockedProfileRead());
                        } else {
                            response = LabSupport.request(request);
                        }
                    } catch (Exception e) {
                        response.put("ok", false);
                        // Never forward raw exception messages: activation
                        // codes, command bytes or unique card IDs may appear.
                        response.put("error", e.getClass().getSimpleName());
                    }
                    writer.write(response.toString());
                    writer.newLine();
                    writer.flush();
                    } // verified socket request scope ALWAYS cleared
                } catch (Throwable ex) {
                    // Avoid leaking card identifiers or stacktraces to disk/logcat.
                    System.err.println("5ber helper connection error=" + ex.getClass().getSimpleName());
                }
            }
        } finally {
            server.close();
        }
    }
}
