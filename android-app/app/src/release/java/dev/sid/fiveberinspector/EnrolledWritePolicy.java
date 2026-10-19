package dev.sid.fiveberinspector;

/** Owner-directed management of a discovered eUICC; no device or card enrollment list. */
public final class EnrolledWritePolicy {
    private EnrolledWritePolicy() {}
    public static boolean isAllowed(String cardIdentity) {
        return ManagementWritePolicy.isAllowed(cardIdentity);
    }
}
