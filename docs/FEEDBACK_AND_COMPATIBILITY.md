# Privacy-first bug reporting (feature branch, not released)

Based on the published 0.19.0-beta.1 application code; does not change the
authenticated ADB command, eUICC operation authorization, or profile write path.

- No background analytics/metrics. The only persistent local switch is
  opt-in minimized crash-summary reporting, off by default.
- Local bounded stage/error ring; no card identifiers, APDUs, activation codes,
  device IDs or raw logging.
- Existing ADB connection screen: report affordance and 2-minute timeout/error
  classification; opening the preview never uploads.
- Missing Binder/failed read: report fields are fixed enum codes, not raw
  exception strings. This cannot distinguish every ROM root cause.
- Settings: manual feedback/email, Diagnostic ZIP export, crash consent toggle.
- GlitchTip receives a one-time structured event only after explicit confirmation.
  On failure, email support/offline ZIP remains available.
- DSN is provided via \`glitchtipDsn\` Gradle user property, not committed. Builds
  without the property have disabled remote submission; do not label those
  builds telemetry-capable.
- Native library allowlist remains untouched; use \`sentry-android-core\`.
- Test with \`JAVA_HOME\`, \`ANDROID_HOME\`: Android Release build + Lint Release,
  existing UI/ADB/compatibility offline tests, and release surface validation.

**Non-goals:** Google Play release, publishing, signing, device data collection,
hardware eUICC write/switch tests, retroactive changes to a published Beta tag.
