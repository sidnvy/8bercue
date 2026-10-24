package dev.sid.fiveberinspector;

import android.net.LocalSocket;
import android.net.Credentials;
import android.os.Process;
import android.net.LocalSocketAddress;

import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/** UID-authenticated local Unix-socket IPC to a user-bootstrapped ADB Shell Companion.
 * The socket is NOT a root or Shizuku substitute on other phones: Motorola
 * SELinux policy may disallow app->Shell connectto. Pixel support is enrolled.
 */
public final class ShellCompanionClient {
    private static final String SOCKET = "fiveber_inspector_readonly_v1";
    private static final int MAX_REQUEST_BYTES = 8192;
    private ShellCompanionClient() {}

    private static int timeout(String method) {
        if (method.equals("executeDownload")) return 300000;
        if (method.equals("executeSwitch") || method.equals("executeOperation")) return 180000;
        if (method.equals("prepareRecoveryReview") || method.equals("confirmRecoveryReview")) return 90000;
        if (method.equals("reconcileOperation") || method.equals("listProfiles")
                || method.equals("getCardInfo") || method.equals("PROFILES")) return 65000;
        return 35000;
    }

    private static JSONObject request(String wire, String method) throws Exception {
        if (wire == null || wire.length() == 0 || wire.length() > MAX_REQUEST_BYTES
                || wire.indexOf('\n') >= 0 || wire.indexOf('\r') >= 0)
            throw new SecurityException("Companion request rejected");
        try (LocalSocket socket = new LocalSocket()) {
            socket.connect(new LocalSocketAddress(SOCKET, LocalSocketAddress.Namespace.ABSTRACT));
            // ALWAYS verify peer UID before sending tokens or activation codes.
            Credentials server = socket.getPeerCredentials();
            if (server == null || server.getUid() != Process.SHELL_UID)
                throw new SecurityException("Unexpected local companion identity");
            socket.setSoTimeout(timeout(method));
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                socket.getOutputStream(), StandardCharsets.UTF_8));
            writer.write(wire);
            writer.newLine();
            writer.flush();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                socket.getInputStream(), StandardCharsets.UTF_8));
            String line = reader.readLine();
            if (line == null || line.length() > 65536)
                throw new IOException("No valid response from Shell helper");
            JSONObject reply = new JSONObject(line);
            if (!reply.optBoolean("ok"))
                throw new IOException("Companion operation not accepted");
            return reply;
        }
    }

    private static JSONObject call(String command) throws Exception {
        switch (command) {
            case "STATUS": case "PROFILES": case "NATIVE_PROFILES":
            case "CAPABILITIES":
                return request(command, command);
            default:
                throw new SecurityException("Unsupported companion command");
        }
    }

    static JSONObject capabilities() throws Exception {
        return call("CAPABILITIES").getJSONObject("result");
    }

    static JSONObject service(String method, Integer index, String nickname,
                              String token, String activationCode,
                              String confirmationCode) throws Exception {
        // Client allowlist is defense in depth. The server separately checks
        // SO_PEERCRED, current package UID, operation and bounded argument types.
        switch (method) {
            case "getStatus": case "listProfiles": case "getCardInfo":
            case "getOperationStatus": case "reconcileOperation":
            case "prepareRecoveryReview": case "confirmRecoveryReview":
            case "prepareCardSelection": case "selectCard":
            case "prepareSwitch": case "executeSwitch": case "prepareRename":
            case "prepareDelete": case "prepareDisable": case "executeOperation":
            case "prepareDownload": case "executeDownload":
            case "prepareNotificationCleanup": case "executeNotificationCleanup":
                break;
            default: throw new SecurityException("Unsupported profile method");
        }
        JSONObject envelope = new JSONObject().put("method", method);
        if (index != null) envelope.put("index", index);
        if (nickname != null) envelope.put("nickname", nickname);
        if (token != null) envelope.put("token", token);
        if (activationCode != null) envelope.put("activationCode", activationCode);
        if (confirmationCode != null) envelope.put("confirmationCode", confirmationCode);
        return request(envelope.toString(),method).getJSONObject("result");
    }

    public static String status() throws Exception {
        return call("STATUS").getString("status");
    }
    public static JSONObject list() throws Exception {
        return call("PROFILES").getJSONObject("result");
    }
    public static JSONObject nativeProfiles() throws Exception {
        return call("NATIVE_PROFILES").getJSONObject("result");
    }
}