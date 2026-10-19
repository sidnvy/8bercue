package dev.sid.fiveberinspector;

/** Identity admission, not an issuer grant. Caller authentication, explicit confirmation,
 * snapshot binding and operation-specific checks are enforced by the transaction service. */
public final class ManagementWritePolicy {
    private ManagementWritePolicy() {}
    public static boolean isAllowed(String identity) {
        return identity != null && identity.matches("[a-fA-F0-9]{64}");
    }
}
