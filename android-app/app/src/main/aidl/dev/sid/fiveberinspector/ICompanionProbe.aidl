package dev.sid.fiveberinspector;

/** Separate READ-ONLY contract for autonomous Binder transport qualification. */
interface ICompanionProbe {
    String getStatus();
    String listProfiles();
    // Probe only; never changes card enrollment or exposes a mutating method.
    String detectKnownCard();
}
