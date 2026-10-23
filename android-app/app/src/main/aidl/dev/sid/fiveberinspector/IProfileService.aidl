package dev.sid.fiveberinspector;

interface IProfileService {
    String getStatus() = 1;
    String listProfiles() = 2;
    // Short-lived, single-use opaque plans; never raw ICCIDs, APDUs or commands.
    String prepareSwitch(int profileIndex) = 3;
    String executeSwitch(String opaqueToken) = 4;
    String prepareRename(int profileIndex, String nickname) = 5;
    String prepareDelete(int profileIndex) = 6;
    String prepareDisable(int profileIndex) = 7;
    String executeOperation(String opaqueToken) = 8;
    String prepareDownload(String activationCode, String confirmationCode) = 9;
    String executeDownload(String opaqueToken) = 10;
    String getOperationStatus() = 11;
    String reconcileOperation() = 12;
    String getCardInfo() = 13;
    String prepareNotificationCleanup() = 14;
    String executeNotificationCleanup(String opaqueToken) = 15;
    String prepareCardSelection() = 16;
    String selectCard(String opaqueChoice) = 17;
    // Explicit, single-use read-only review of an uncertain lifecycle operation.
    String prepareRecoveryReview() = 18;
    String confirmRecoveryReview(String opaqueToken) = 19;
    void destroy() = 16777114;
}
