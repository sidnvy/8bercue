package dev.sid.fiveberinspector;

import android.os.Build;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Volatile diagnostic context, not product analytics. Never automatically uploads.
 * No device identifiers, eUICC payloads, freeform exception messages or request URLs.
 */
final class CompatibilityDiagnostics {
  enum Transport { ADB_COMPANION, SHIZUKU, SHELL_COMPANION, CARD_READ }
  enum Stage { BOOTSTRAP, AUTHORIZATION, BINDER, SERVICE, INVENTORY }
  enum Failure {
    DEVICE_UNSUPPORTED, BOOTSTRAP_INCOMPLETE, BOOTSTRAP_TIMEOUT,
    BINDER_HANDSHAKE_FAILED, SHIZUKU_NOT_RUNNING, SHIZUKU_PERMISSION_DENIED,
    SHIZUKU_BIND_FAILED, SERVICE_DISCONNECTED, COMPANION_UNAVAILABLE,
    INVENTORY_READ_FAILED, INVENTORY_ACCESS_DENIED
  }
  static final class Snapshot {
    final Transport transport;
    final Stage stage;
    final Failure code;
    final String manufacturer, model, buildId, securityPatch, androidRelease;
    final int api;
    final long timestamp;
    Snapshot(Transport t, Stage s, Failure c, String manufacturer, String model,
             String buildId, String securityPatch, String release, int api) {
      transport=t;stage=s;code=c;
      this.manufacturer=clean(manufacturer);
      this.model=clean(model);
      this.buildId=clean(buildId);
      this.securityPatch=securityPatch != null && securityPatch.matches("\\d{4}-\\d{2}-\\d{2}")
          ? securityPatch : "unknown";
      androidRelease=clean(release);
      this.api=api;
      timestamp=System.currentTimeMillis();
    }
    String summary() {
      return "Error: " + code.name() + "\nStage: " + stage.name()
          + "\nTransport: " + transport.name() + "\nDevice: " + manufacturer + " " + model
          + "\nAndroid: " + androidRelease + " (API " + api + ")"
          + "\nROM Build ID: " + buildId + "\nSecurity patch: " + securityPatch
          + "\nApp: 8bercue " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")\n";
    }
    Map<String,String> tags() {
      Map<String,String> values = new LinkedHashMap<>();
      values.put("reason", code.name());
      values.put("stage", stage.name());
      values.put("transport", transport.name());
      values.put("manufacturer", manufacturer);
      values.put("device_model", model);
      values.put("android_release", androidRelease);
      values.put("android_api", String.valueOf(api));
      values.put("rom_build_id", buildId);
      values.put("security_patch", securityPatch);
      return values;
    }
    JSONObject json() throws JSONException {
      JSONObject obj = new JSONObject();
      for(Map.Entry<String,String> e : tags().entrySet()) obj.put(e.getKey(),e.getValue());
      obj.put("schema_version", 1);
      return obj;
    }
  }

  private static final ArrayDeque<String> trace = new ArrayDeque<>();
  private static final int MAX_STAGES = 24;
  private static Snapshot lastFailure;
  private static long attempt;
  private CompatibilityDiagnostics() {}
  private static String clean(String value) {
    if (value == null || !value.matches("[A-Za-z0-9 ._+\\-]{1,64}")) return "unknown";
    return value;
  }
  private static void note(String value) {
    if (trace.size() >= MAX_STAGES) trace.removeFirst();
    trace.addLast(value);
  }
  static synchronized long started(Transport transport) {
    lastFailure = null;
    attempt++;
    note(transport.name()+":STARTED");
    return attempt;
  }
  static synchronized boolean isCurrent(long candidate) {return candidate == attempt;}
  static synchronized void ready(Transport transport) {
    attempt++;
    lastFailure=null;
    note(transport.name()+":READY");
  }
  static synchronized Snapshot failed(Transport transport, Stage stage, Failure code) {
    attempt++;
    note(transport.name()+":"+code.name());
    lastFailure=new Snapshot(transport,stage,code,Build.MANUFACTURER,Build.MODEL,
        Build.ID,Build.VERSION.SECURITY_PATCH,Build.VERSION.RELEASE,Build.VERSION.SDK_INT);
    return lastFailure;
  }
  static synchronized Snapshot lastFailure() {return lastFailure;}
  static synchronized String stageSummary() {
    return String.join(" > ", trace);
  }
  static synchronized String traceJson() {
    org.json.JSONArray array=new org.json.JSONArray();
    for(String e:trace) array.put(e);
    return array.toString();
  }
}
