package dev.sid.fiveberinspector;
/** Missing/error recovery evidence must never make mutation controls look available. */
final class RecoveryUiPolicy {
    static boolean blocks(boolean ok, boolean historyRequiresReview, boolean reviewRequired, String status) {
        return !ok || historyRequiresReview || reviewRequired
            || !("idle".equals(status) || "verified".equals(status) || "not_completed".equals(status) || "reviewed_abandoned".equals(status) || "reviewed_observed".equals(status));
    }
    static boolean keepsPending(boolean recoveryBlocked,boolean notificationReviewRequired) {return recoveryBlocked||notificationReviewRequired;}
    static boolean needsCardChoice(String capabilityError){return "AMBIGUOUS_EUICC".equals(capabilityError);}
    private RecoveryUiPolicy() {}
}