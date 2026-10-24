package dev.sid.fiveberinspector;

import org.json.JSONObject;

/** App-local AIDL implementation routing existing UI transactions to the
 * UID-verified Shell Companion v2. Each call is a separate Unix Socket request;
 * the server keeps the short-lived operation token and journal.
 * No caller-selected APDU, ICCID, Shell command or physical card reader.
 */
final class CompanionProfileBackend extends IProfileService.Stub {
    private String result(String method, Integer index, String nickname,
                          String token, String activation, String confirmation,
                          boolean mayHaveStartedWrite) {
        try {
            JSONObject reply = ShellCompanionClient.service(
                method, index, nickname, token, activation, confirmation);
            if (reply.length() == 0) throw new IllegalStateException("Empty response");
            return reply.toString();
        } catch (Exception failure) {
            // A transport error AFTER an execute request is not evidence of
            // failure; it could have already modified the eUICC.
            try {
                return new JSONObject().put("ok", false)
                    .put("status", mayHaveStartedWrite ? "unknown" : "not_started")
                    .put("reviewRequired", mayHaveStartedWrite)
                    .put("error", failure.getClass().getSimpleName())
                    .toString();
            } catch (Exception ignored) {
                return "{\"ok\":false,\"status\":\"unknown\",\"reviewRequired\":true}";
            }
        }
    }
    private String result(String method) {
        return result(method,null,null,null,null,null,false);
    }

    @Override public String getStatus() {
        try {
            return ShellCompanionClient.service("getStatus",null,null,null,null,null)
                    .optString("status", "Companion status unavailable");
        } catch (Exception e) { return "Companion disconnected"; }
    }
    @Override public String prepareCardSelection() { return result("prepareCardSelection"); }
    @Override public String selectCard(String token) {
        return result("selectCard",null,null,token,null,null,false);
    }
    @Override public String listProfiles() { return result("listProfiles"); }
    @Override public String getCardInfo() { return result("getCardInfo"); }
    @Override public String getOperationStatus() { return result("getOperationStatus"); }
    @Override public String reconcileOperation() { return result("reconcileOperation"); }
    @Override public String prepareRecoveryReview() { return result("prepareRecoveryReview"); }
    @Override public String confirmRecoveryReview(String token) {
        return result("confirmRecoveryReview",null,null,token,null,null,false);
    }
    @Override public String prepareSwitch(int index) {
        return result("prepareSwitch",index,null,null,null,null,false);
    }
    @Override public String executeSwitch(String token) {
        return result("executeSwitch",null,null,token,null,null,true);
    }
    @Override public String prepareRename(int index, String nickname) {
        return result("prepareRename",index,nickname,null,null,null,false);
    }
    @Override public String prepareDelete(int index) {
        return result("prepareDelete",index,null,null,null,null,false);
    }
    @Override public String prepareDisable(int index) {
        return result("prepareDisable",index,null,null,null,null,false);
    }
    @Override public String executeOperation(String token) {
        return result("executeOperation",null,null,token,null,null,true);
    }
    @Override public String prepareDownload(String activationCode, String confirmationCode) {
        return result("prepareDownload",null,null,null,activationCode,confirmationCode,false);
    }
    @Override public String executeDownload(String token) {
        return result("executeDownload",null,null,token,null,null,true);
    }
    @Override public String prepareNotificationCleanup() {
        return result("prepareNotificationCleanup");
    }
    @Override public String executeNotificationCleanup(String token) {
        return result("executeNotificationCleanup",null,null,token,null,null,true);
    }
    @Override public void destroy() {
        // In-process adapter cannot destroy the shared Shell UID process.
        // Only the explicit ADB bootstrap can replace/stop the companion.
    }
}