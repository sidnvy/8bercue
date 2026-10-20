package dev.sid.fiveberinspector;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.ArrayDeque;
import org.json.JSONArray;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONObject;

/** Source allowlist: never serialize arbitrary logs, exceptions or card objects. */
final class SafeDiagnosticBundle {
  private SafeDiagnosticBundle() {}
  private static final int MAX_STEPS = 24;
  private static final ArrayDeque<String> steps = new ArrayDeque<>();
  enum Step { APP_STARTED, REPORT_OPENED, ZIP_REQUESTED, ZIP_SAVED,
              CONNECTION_AVAILABLE, CONNECTION_UNAVAILABLE }
  static synchronized void mark(Step step) {
    if (steps.size() == MAX_STEPS) steps.removeFirst();
    steps.addLast(step.name()); // enum-only: no dynamic card or user data
  }
  static synchronized String stepsJson() {
    JSONArray array = new JSONArray();
    for (String step : steps) array.put(step);
    return array.toString();
  }
  static String summary(String version, int build, int api, String model, boolean connected, boolean pending) {
    // Avoid freeform device strings in diagnostic artifacts.
    return "App: 8bercue " + version + " (" + build + ")\nAndroid API: " + api
        + "\nConnection available: " + connected
        + "\nRecovery verification pending: " + pending + "\n";
  }
  static void write(OutputStream output, String version, int build, int api,
                    String model, boolean connected, boolean pending) throws IOException {
    try (ZipOutputStream zip = new ZipOutputStream(output)) {
      JSONObject manifest = new JSONObject();
      JSONObject environment = new JSONObject();
      try {
        manifest.put("schema_version", 1);
        manifest.put("report_id", UUID.randomUUID().toString());
        manifest.put("generated_at_ms", System.currentTimeMillis());
        manifest.put("privacy", "allowlisted-local-export-no-raw-logs");
        environment.put("app", "8bercue");
        environment.put("version", version);
        environment.put("build", build);
        environment.put("android_api", api);
        environment.put("connection_available", connected);
        environment.put("recovery_pending", pending);
      } catch (org.json.JSONException impossible) {
        throw new IOException("Could not encode diagnostics", impossible);
      }
      entry(zip, "manifest.json", manifest.toString());
      entry(zip, "environment.json", environment.toString());
      entry(zip, "breadcrumbs.json", stepsJson());
      CompatibilityDiagnostics.Snapshot issue = CompatibilityDiagnostics.lastFailure();
      if (issue != null) {
        try { entry(zip, "compatibility.json", issue.json().toString()); }
        catch (org.json.JSONException err) {throw new IOException("Invalid compatibility report", err);}
      }
      entry(zip, "connection-stages.json", CompatibilityDiagnostics.traceJson());
      entry(zip, "README.txt", "8bercue safe diagnostics. No card identifiers, profile data, activation codes, APDU or logcat are collected. Review before sharing.\n");
    }
  }
  private static void entry(ZipOutputStream zip, String name, String text) throws IOException {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > 8192) throw new IOException("Diagnostic entry too large");
    zip.putNextEntry(new ZipEntry(name));
    zip.write(bytes);
    zip.closeEntry();
  }
}
