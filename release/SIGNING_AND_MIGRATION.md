# Signing and migration

Status: **limited Beta distribution authorized; stable/production qualification remains incomplete**.
The current direct-distribution Beta is versionCode 52 /
[0.20.0-beta.4](https://github.com/sidnvy/8bercue/releases/tag/v0.20.0-beta.4).
The protected workflow prepares a draft; public promotion follows acceptance of
the exact signed APK under [BETA-POLICY.md](../BETA-POLICY.md). See the release's
`FINAL-ACCEPTANCE.json` for tested operations and limits.

The application ID remains `dev.sid.fiveberinspector`. Updates use the existing
owner-authorized RSA-4096 production identity. Public certificate SHA-256:
`24aa7984b0d0977ff1e87b57beca91b2e36a05c04a5443f1a47aa23d90a6ef1c`.
The encrypted local backup passed a private-key restore check; off-device recovery
custody remains to be established.

The build accepts `FIVEBER_RELEASE_KEYSTORE`, `FIVEBER_RELEASE_STORE_PASSWORD`, `FIVEBER_RELEASE_KEY_ALIAS`, and `FIVEBER_RELEASE_KEY_PASSWORD` together. Partial configuration fails. Supply secrets through the release owner's protected environment, not a checked-in file, command arguments, build log, issue or chat. With no production inputs, release APK/AAB builds remain unsigned. Debug continues using the existing explicit lab key; it must never become a production identity by default.

Before creating a new publishing identity, the owner must assign its long-lived identity and recovery custodian. Record the public SHA-256 certificate fingerprint in the release policy, protect the private key separately, and verify a recovery exercise without publishing private material. Play upload and app-signing identities are distinct when Play App Signing is used; direct APK and Play upgrade continuity must be decided before the first public installation.

Run `android-app/tools/verify_artifact.py` with the selected SDK build-tools directory. `--allow-unsigned` reports engineering validation only. Production validation requires `--expected-signer-sha256` and should additionally use `--deny-signer-sha256` for every known test certificate. Reject a debuggable APK, unsupported ABI, wrong package/SDK policy, invalid signature, absent notices or inconsistent native payload. Never treat successful signing as a substitute for exact-artifact device acceptance or stable qualification.

An existing test installation cannot normally be replaced by the same application ID signed with an unrelated key. Do not uninstall, clear app data, force a downgrade, or delete privileged journal state to bypass this. First resolve all in-flight/unknown transactions, independently observe the card, and prepare a user-reviewed migration. App uninstallation is not profile deletion, but it can lose local UI state and does not automatically remove Shell-owned transaction records. A formal migration must preserve the safety history and verify the same card after reconnecting. The RC gate must exercise the chosen path with a dedicated test installation before public instructions are issued.

Keep versionCode monotonically increasing for updates. Prefer a forward fix with a higher versionCode and the approved signer. Do not promise APK downgrade as rollback: software rollback does not undo a card operation. Never restore a card profile automatically in response to an app rollback or uncertain journal state.

Direct distribution needs an immutable signed APK, checksums, support limits, release notes, in-app ADB connection instructions, notices, approved privacy contact and corresponding-source package. Play additionally needs a verified AAB, app-signing policy, device filtering, current target API/policy review, Data Safety and store declarations. The owner approved direct APK distribution preparation. The owner separately authorized the 0.19.0-beta.1 GitHub release and remote source push on 2026-10-20. This does not authorize Play publication or certify unperformed tests.
