package dev.sid.fiveberinspector;

import android.app.Application;

/** Startup has no eUICC, telephony or privileged-service operations. */
public final class SafeApp extends Application {
  @Override protected void attachBaseContext(android.content.Context base) {
    super.attachBaseContext(base);
    // This app supports the Shell identity only; never auto-initialize root/Sui.
    rikka.shizuku.ShizukuProvider.disableAutomaticSuiInitialization();
  }
  @Override public void onCreate() {
    super.onCreate();
    SafeTelemetry.startIfConsented(this);
  }
}
