package dev.sid.fiveberinspector;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import io.sentry.Sentry;
import io.sentry.SentryEvent;
import io.sentry.SentryLevel;
import io.sentry.android.core.SentryAndroid;
import io.sentry.protocol.Message;
import java.util.Arrays;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * Opt-in crash summary only. The original throwable, exception message, stack,
 * breadcrumbs, contexts, user, request and extras never leave beforeSend.
 */
final class SafeTelemetry {
  private static boolean started;
  private static boolean manualOnly;
  private static final String PREF = "crash_reporting";
  private SafeTelemetry() {}

  static boolean allowed(Context context) {
    return context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("enabled", false);
  }

  static void setAllowed(Context context, boolean enabled) {
    context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .edit().putBoolean("enabled", enabled).apply();
    if (enabled) startIfConsented(context.getApplicationContext());
    else {
      try { Sentry.close(); } catch (RuntimeException ignored) {}
      started = false;
    }
  }

  static synchronized boolean startIfConsented(Context context) {
    if (started) return true;
    if (!allowed(context)) return false;
    return initialize(context, false);
  }

  private static synchronized boolean initialize(Context context, boolean oneShot) {
    if (started) return true;
    String dsn = readDsn(context);
    if (dsn.isEmpty() || !dsn.startsWith("https://")) return false;
    manualOnly = oneShot;
    try {
      SentryAndroid.init(context, options -> {
        options.setDsn(dsn);
        options.setDebug(BuildConfig.DEBUG);
        options.setRelease("8bercue@" + BuildConfig.VERSION_NAME + "+" + BuildConfig.VERSION_CODE);
        options.setEnvironment(BuildConfig.DEBUG ? "qa" : "production");
        options.setSendDefaultPii(false);
        options.setEnableAutoSessionTracking(false);
        options.setEnableUncaughtExceptionHandler(!oneShot);
        options.setTracesSampleRate(0.0);
        options.setMaxBreadcrumbs(0);
        options.setAttachScreenshot(false);
        options.setAttachViewHierarchy(false);
        options.setAttachAnrThreadDump(false);
        options.setAttachRawTombstone(false);
        options.setEnableNdk(false); // native crash dump may include protocol secrets
        options.setEnableActivityLifecycleBreadcrumbs(false);
        options.setEnableAppLifecycleBreadcrumbs(false);
        options.setEnableSystemEventBreadcrumbs(false);
        options.setEnableNetworkEventBreadcrumbs(false);
        options.setEnableUserInteractionBreadcrumbs(false);
        options.setCollectAdditionalContext(false);
        options.setCollectExternalStorageContext(false);
        options.setBeforeSend((event, hint) -> sanitize(event));
      });
      started = true;
      return true;
    } catch (RuntimeException unavailable) {
      started = false; manualOnly=false; return false;
    }
  }

  /** Make a completely fresh envelope: never forward SDK's original exception fields. */
  static SentryEvent sanitize(SentryEvent event) {
    if ("8bercue.compatibility".equals(event.getLogger())) {
      // Never copy arbitrary event context or extras; tags are strictly allowlisted.
      SentryEvent safe = new SentryEvent();
      safe.setLogger("8bercue.compatibility");
      safe.setRelease("8bercue@" + BuildConfig.VERSION_NAME + "+" + BuildConfig.VERSION_CODE);
      safe.setEnvironment(BuildConfig.DEBUG ? "qa" : "production");
      safe.setLevel(SentryLevel.WARNING);
      Map<String,String> tags = new LinkedHashMap<>();
      for (String key : Arrays.asList("reason","stage","transport","manufacturer",
          "device_model","android_release","android_api","rom_build_id","security_patch")) {
        String value = event.getTags() == null ? null : event.getTags().get(key);
        tags.put(key, safeTag(value));
      }
      safe.setTags(tags);
      Message msg = new Message();
      msg.setMessage("8bercue.compatibility." + tags.get("reason"));
      safe.setMessage(msg);
      safe.setFingerprints(Arrays.asList("8bercue-compatibility-v1",
          tags.get("reason"), tags.get("transport")));
      return safe;
    }
    if (manualOnly) return null; // no unsolicited events in manual-only session
    String type = "UnknownFailure";
    String location = "unknown";
    Throwable throwable = event.getThrowable();
    if (throwable != null) {
      type = safeToken(throwable.getClass().getSimpleName(), "UnknownFailure");
      for (StackTraceElement frame : throwable.getStackTrace()) {
        if (frame.getClassName().startsWith("dev.sid.fiveberinspector.")) {
          location = safeToken(frame.getClassName() + "." + frame.getMethodName(), "unknown");
          break;
        }
      }
    } else if (event.getExceptions() != null && !event.getExceptions().isEmpty()) {
      type = safeToken(event.getExceptions().get(0).getType(), "UnknownFailure");
    } else if (event.getMessage() != null
        && "8bercue.telemetry_smoke_test".equals(event.getMessage().getMessage())) {
      type = "ConnectivityTest";
    }
    SentryEvent safe = new SentryEvent();
    safe.setLevel(SentryLevel.ERROR);
    safe.setLogger("8bercue.safe-crash");
    Message message = new Message();
    message.setMessage("8bercue." + type + " at " + location);
    safe.setMessage(message);
    safe.setFingerprints(Arrays.asList("8bercue-v1", type, location));
    return safe;
  }

  private static String safeTag(String value) {
    return value != null && value.matches("[A-Za-z0-9 ._+\\-]{1,64}")
        ? value : "unknown";
  }

  /** A user-initiated one-shot event does not change persistent crash consent. */
  static boolean submitCompatibility(Context context, CompatibilityDiagnostics.Snapshot report) {
    if (report == null) return false;
    boolean temporary = !allowed(context);
    if (!initialize(context.getApplicationContext(), temporary)) return false;
    try {
      SentryEvent event = new SentryEvent();
      event.setLogger("8bercue.compatibility");
      event.setLevel(SentryLevel.WARNING);
      event.setTags(report.tags());
      io.sentry.protocol.SentryId id = Sentry.captureEvent(event);
      Sentry.flush(5000);
      return id != null && !id.equals(io.sentry.protocol.SentryId.EMPTY_ID);
    } catch (RuntimeException unavailable) {
      return false;
    } finally {
      if (temporary) {
        Sentry.close();
        started = false;
        manualOnly = false;
      }
    }
  }

  static boolean configured(Context context) {return !readDsn(context).isEmpty();}

  private static String safeToken(String value, String fallback) {
    return value != null && value.matches("[A-Za-z0-9_.$]{1,150}") ? value : fallback;
  }

  private static String readDsn(Context context) {
    try {
      ApplicationInfo info = context.getPackageManager().getApplicationInfo(
          context.getPackageName(), PackageManager.GET_META_DATA);
      return info.metaData == null ? "" : info.metaData.getString("io.sentry.dsn", "");
    } catch (PackageManager.NameNotFoundException ignored) {
      return "";
    }
  }

  static boolean smoke(Context context) {
    if (!startIfConsented(context.getApplicationContext())) return false;
    Sentry.captureMessage("8bercue.telemetry_smoke_test", SentryLevel.ERROR);
    return true; // queued, not evidence of confirmed server ingestion
  }
}
