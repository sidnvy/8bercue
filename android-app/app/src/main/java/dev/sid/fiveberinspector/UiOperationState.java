package dev.sid.fiveberinspector;

/** Process-local coordination only. Never stores credentials, tokens or card identifiers. */
final class UiOperationState {
  private long next, active, completed;
  private String title = "";
  private boolean mutation, completedMutation, needsReconciliation;
  private long claimedCompletion;
  synchronized long begin(String label, boolean write) {
    if (active != 0)
      return 0;
    active = ++next;
    title = label;
    mutation = write;
    return active;
  }
  synchronized void finish(long ticket) {
    finish(ticket, false);
  }
  synchronized void finish(long ticket, boolean ownerHandlesResult) {
    if (ticket == 0 || ticket != active)
      return;
    completed = ticket;
    completedMutation = mutation;
    needsReconciliation = !ownerHandlesResult;
    active = 0;
    title = "";
  }
  synchronized boolean claimReconciliation(boolean resumed) {
    if (!resumed || active != 0 || !needsReconciliation || completed == claimedCompletion)
      return false;
    claimedCompletion = completed;
    needsReconciliation = false;
    return true;
  }
  synchronized boolean running() {
    return active != 0;
  }
  synchronized String title() {
    return title;
  }
  synchronized long completed() {
    return completed;
  }
  synchronized boolean completedMutation() {
    return completedMutation;
  }
}
