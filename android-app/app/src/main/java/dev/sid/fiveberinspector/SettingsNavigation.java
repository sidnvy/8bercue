package dev.sid.fiveberinspector;

/** Display-only destinations. Restoring a page never restores a command or profile action. */
final class SettingsNavigation {
  static final int CONNECTION = 7, APPEARANCE = 8, SUPPORT = 9, ADVANCED = 10,
      PRIVACY = 11, CARD = 12, LAB = 13, RECOVERY = 14;

  static int parent(int screen) {
    return switch (screen) {
      case 5, CONNECTION, APPEARANCE, SUPPORT, ADVANCED, PRIVACY -> 2;
      case 6 -> CONNECTION;
      case CARD, LAB, RECOVERY -> ADVANCED;
      default -> 0;
    };
  }

  static int restore(int screen, boolean laboratory) {
    if (screen == LAB && !laboratory) return ADVANCED;
    if (screen == RECOVERY && !laboratory) return 5; // contextual operation records, no generic recovery tool
    // Computer connection needs a new explicit two-minute authorization window.
    if (screen == 6) return CONNECTION;
    return screen == 2 || screen == 5 || (screen >= CONNECTION && screen <= RECOVERY)
        ? screen : 0;
  }

  private SettingsNavigation() {}
}
