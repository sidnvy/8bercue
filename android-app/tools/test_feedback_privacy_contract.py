#!/usr/bin/env python3
from pathlib import Path
src=Path(__file__).resolve().parents[1]/"app/src/main/java/dev/sid/fiveberinspector"
ui=(src/"MainActivity.java").read_text()
ui += (Path(__file__).resolve().parents[1]/"app/src/main/res/values/strings.xml").read_text()
telemetry=(src/"SafeTelemetry.java").read_text()
diag=(src/"CompatibilityDiagnostics.java").read_text()
bundle=(src/"SafeDiagnosticBundle.java").read_text()
manifest=(src.parents[0]/"AndroidManifest.xml").read_text() if False else (Path(__file__).resolve().parents[1]/"app/src/main/AndroidManifest.xml").read_text()
assert "Trouble connecting? Report issue" in ui
assert "Report previous connection failure" in ui
assert "Report a problem" in ui and "R.string.nav_report_problem" in ui
assert "Send diagnostic report" in ui
assert "CompatibilityDiagnostics.Failure.BOOTSTRAP_TIMEOUT" in ui
assert "CompatibilityDiagnostics.Failure.INVENTORY_READ_FAILED" in ui
assert "BOOTSTRAP_TIMEOUT" in diag
assert "private static final int MAX_STAGES = 24" in diag
assert "Build.FINGERPRINT" not in diag
assert "Build.SERIAL" not in diag
assert "getDeviceId" not in diag
assert "Save diagnostic ZIP" in ui and '"compatibility.json"' in bundle
assert "io.sentry.auto-init" in manifest and 'android:value="false"' in manifest
assert "options.setBeforeSend((event, hint) -> sanitize(event))" in telemetry
assert "Sentry.close();" in telemetry and "manualOnly" in telemetry
assert "setAttachScreenshot(false)" in telemetry
assert "new SentryEvent()" in telemetry
assert "sentry-android-core:8.61.0" in (Path(__file__).resolve().parents[1]/"app/build.gradle").read_text()
print("PASS feedback/compatibility structural privacy contract")
