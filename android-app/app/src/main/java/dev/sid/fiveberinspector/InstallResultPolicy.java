package dev.sid.fiveberinspector;

/** Success presentation requires an independently verified, explicitly disabled install. */
final class InstallResultPolicy {
  private InstallResultPolicy() {}
  static boolean verified(String status, Boolean installed, Boolean enabled, boolean reviewRequired) {
    return "installed_disabled".equals(status) && Boolean.TRUE.equals(installed)
        && Boolean.FALSE.equals(enabled) && !reviewRequired;
  }
}
