package dev.sid.fiveberinspector;

import android.app.Application;

/** Startup has no eUICC, telephony or privileged-service operations. */
public final class SafeApp extends Application {
  @Override public void onCreate() {
    super.onCreate();
    SafeTelemetry.startIfConsented(this);
  }
}
