package dev.sid.fiveberinspector;

import org.json.JSONObject;

/**
 * Narrow JSON protocol for the UID-authenticated ADB Shell Companion.
 *
 * This is NOT a generic Binder, Shell, APDU, ICCID or filesystem RPC. The
 * companion's server checks SO_PEERCRED before invoking any of these methods.
 * Profile mutations delegate to the EXISTING EID-enrolled, two-phase, journaled
 * Shell backend implementation running at the same Shell UID2000.
 */
final class CompanionProfileRpc {
    static final int PROTOCOL_VERSION = 2;
    static final int MAX_INPUT_BYTES = 8192;

    private CompanionProfileRpc() {}

    static String readBoundedLine(java.io.BufferedReader reader) throws Exception {
        StringBuilder result = new StringBuilder();
        for (int n = 0; n <= MAX_INPUT_BYTES; n++) {
            int ch = reader.read();
            if (ch < 0) {
                if (n == 0) throw new SecurityException("Request missing");
                break;
            }
            if (ch == '\n') break;
            if (ch == '\r' || ch == 0 || n == MAX_INPUT_BYTES)
                throw new SecurityException("Request limit or control character");
            result.append((char)ch);
        }
        if (result.length() == 0)
            throw new SecurityException("Empty request");
        return result.toString();
    }

    static JSONObject capabilities() throws Exception {
        return new JSONObject()
                .put("version", PROTOCOL_VERSION)
                .put("genericProfileManagement", true)
                .put("connectionMethod", "adb_shell")
                .put("requiresAdbShellBootstrap", true)
                .put("peerCredentialRequired", true)
                .put("transactionReviewRequired", true)
                .put("arbitraryShellAllowed", false)
                .put("arbitraryApduAllowed", false);
    }

    private static int index(JSONObject args) throws Exception {
        if (!args.has("index") || args.isNull("index"))
            throw new SecurityException("Missing index");
        Object raw = args.get("index");
        if (!(raw instanceof Integer) && !(raw instanceof Long))
            throw new SecurityException("Invalid index type");
        int value = args.getInt("index");
        if (value < 0 || value > 15)
            throw new SecurityException("Profile index out of range");
        return value;
    }

    private static String text(JSONObject args, String key, int max, boolean nonempty) throws Exception {
        if (!args.has(key) || args.isNull(key) || !(args.get(key) instanceof String))
            throw new SecurityException("Missing or invalid field");
        String value = args.getString(key);
        if (value.length() > max || (nonempty && value.length() == 0))
            throw new SecurityException("Field length rejected");
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 ||
            value.indexOf('\u0000') >= 0)
            throw new SecurityException("Control character rejected");
        return value;
    }

    private static String token(JSONObject args) throws Exception {
        String value = text(args, "token", 48, true);
        if (!OperationTokens.isValid(value))
            throw new SecurityException("Invalid one-use token");
        return value;
    }

    private static JSONObject parse(String reply) throws Exception {
        if (reply == null || reply.length() > 65536)
            throw new SecurityException("Unexpected service reply");
        return new JSONObject(reply);
    }

    static JSONObject execute(ProfileUserService backend, JSONObject request) throws Exception {
        String method = text(request, "method", 45, true);
        // Only the named methods below can reach the trusted service. The
        // parameters remain typed and bounded: no arbitrary token commands.
        JSONObject result;
        switch (method) {
            case "getStatus":
                result = new JSONObject().put("status", backend.getStatus());
                break;
            case "listProfiles":
                result = parse(backend.listProfiles());
                break;
            case "getCardInfo":
                result = parse(backend.getCardInfo());
                break;
            case "getOperationStatus":
                result = parse(backend.getOperationStatus());
                break;
            case "reconcileOperation":
                result = parse(backend.reconcileOperation());
                break;
            case "prepareCardSelection":
                result = parse(backend.prepareCardSelection());
                break;
            case "selectCard":
                result = parse(backend.selectCard(token(request)));
                break;
            case "prepareRecoveryReview":
                result = parse(backend.prepareRecoveryReview());
                break;
            case "confirmRecoveryReview":
                result = parse(backend.confirmRecoveryReview(token(request)));
                break;
            case "prepareSwitch":
                result = parse(backend.prepareSwitch(index(request)));
                break;
            case "executeSwitch":
                result = parse(backend.executeSwitch(token(request)));
                break;
            case "prepareRename":
                // Empty nicknames are valid ES10c reset values. Rejecting
                // them would make an otherwise reversible rename impossible
                // to roll back to the original blank nickname.
                result = parse(backend.prepareRename(index(request), text(request, "nickname", 64, false)));
                break;
            case "prepareDelete":
                result = parse(backend.prepareDelete(index(request)));
                break;
            case "prepareDisable":
                result = parse(backend.prepareDisable(index(request)));
                break;
            case "executeOperation":
                result = parse(backend.executeOperation(token(request)));
                break;
            case "prepareDownload":
                result = parse(backend.prepareDownload(
                    text(request, "activationCode", 2048, true),
                    text(request, "confirmationCode", 256, false)));
                break;
            case "executeDownload":
                result = parse(backend.executeDownload(token(request)));
                break;
            case "prepareNotificationCleanup":
                result = parse(backend.prepareNotificationCleanup());
                break;
            case "executeNotificationCleanup":
                result = parse(backend.executeNotificationCleanup(token(request)));
                break;
            default:
                throw new SecurityException("Unsupported profile RPC");
        }
        return new JSONObject().put("ok", true).put("result", result);
    }
}