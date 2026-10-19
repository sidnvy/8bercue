package dev.sid.fiveberinspector;

/**
 * Ephemeral UI selection guard. A Profile action must originate from the same
 * inventory and backend instance which supplied its index, not a stale row.
 * Deliberately never persists card identity, nickname or operation tokens.
 */
final class ProfileDetailSession {
  private Object inventory, backend, profile;
  private int index = -1;

  void open(Object inventory, Object backend, Object profile, int index) {
    if (inventory == null || profile == null || index < 0) {
      clear();
      throw new IllegalArgumentException("Invalid profile selection");
    }
    this.inventory = inventory;
    this.backend = backend;
    this.profile = profile;
    this.index = index;
  }

  boolean matches(Object inventory, Object backend, Object profile, int index) {
    return this.profile != null && this.inventory == inventory
        && this.backend == backend && this.profile == profile && this.index == index;
  }

  boolean isOpen() {
    return profile != null;
  }

  void clear() {
    inventory = null;
    backend = null;
    profile = null;
    index = -1;
  }
}
