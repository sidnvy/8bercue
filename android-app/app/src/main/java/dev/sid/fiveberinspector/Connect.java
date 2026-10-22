package dev.sid.fiveberinspector;

import android.os.Bundle;
import android.os.Looper;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

/** Foreground ADB status client. Its child is the existing authenticated, typed service. */
public final class Connect {
  public static void main(String[] args) {
    int result;
    try { result = run(args); }
    catch (Exception failure) { System.err.println(ConnectionFeedback.message(ConnectionFeedback.code(failure))); result = 1; }
    System.out.flush(); System.err.flush(); System.exit(result);
  }
  private static Bundle call(Object provider, String method, Bundle extras) throws Exception {
    return CompanionManagementStarter.call(provider, method, extras);
  }
  private static boolean connected(Object provider) throws Exception {
    Bundle state = call(provider, CompanionBinderProvider.STATE_METHOD, new Bundle());
    if (state == null || state.getInt("protocol") != CompanionBinderProvider.MANAGEMENT_VERSION
        || state.getInt("versionCode") != BuildConfig.VERSION_CODE) throw new SecurityException("App version mismatch");
    return state.getBoolean("connected");
  }
  private static void feedback(Object provider, Bundle challenge, String code) {
    try {
      Bundle report = new Bundle(challenge); report.putString("code", code);
      call(provider, CompanionBinderProvider.FEEDBACK_METHOD, report);
    } catch (Exception ignored) { /* Expired/consumed challenges cannot alter app status. */ }
  }
  private static int run(String[] args) throws Exception {
    if (android.os.Process.myUid() != 2000 || args.length != 0) {
      System.err.println("Run this command through authorized ADB; no root or Shizuku is required."); return 1;
    }
    Process owner = new ProcessBuilder("/system/bin/am", "get-current-user").redirectErrorStream(true).start();
    if (!owner.waitFor(5, TimeUnit.SECONDS)) { owner.destroyForcibly(); throw new SecurityException("Owner lookup timed out"); }
    String user;
    try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(owner.getInputStream(), StandardCharsets.UTF_8))) {
      String line = reader.readLine(); user = line == null ? "" : line.trim();
    }
    if (owner.exitValue() != 0 || !"0".equals(user)) {
      System.err.println("Switch to the phone's main user and open 8bercue there before connecting."); return 1;
    }
    String apk = System.getenv("CLASSPATH");
    if (!ConnectionCommand.validApk(apk) || !apk.equals(NativeLpacBundle.installedApk()))
      throw new SecurityException("Companion artifact mismatch");
    Looper.prepareMainLooper();
    try (SystemProviderBridge.Connection connection = SystemProviderBridge.acquire("dev.sid.fiveberinspector.companion")) {
      Object provider = connection.provider;
      boolean alreadyConnected=connected(provider);
      if(CardTransactionLock.persistentQuarantine()){
        // An active app challenge is required before retiring this app's stale backends.
        Bundle permission;
        try{permission=call(provider,CompanionBinderProvider.CHALLENGE_METHOD,new Bundle());}
        catch(Exception absent){
          if(alreadyConnected){System.out.println("8bercue is already connected. Return to the app to check card access.");return 0;}
          throw absent;
        }
        if(permission==null||permission.getInt("protocol")!=CompanionBinderProvider.MANAGEMENT_VERSION
            ||permission.getInt("versionCode")!=BuildConfig.VERSION_CODE||!OperationTokens.isValid(permission.getString("nonce")))
          throw new SecurityException("Invalid bootstrap challenge");
        System.out.println("Checking previous 8bercue workers for read-only recovery…");System.out.flush();
        try{ReadOnlyRecovery.beforeConnect();}
        catch(Exception blocked){System.err.println("Read-only recovery is blocked: old workers or recovery evidence could not be verified. No safety records or profiles were changed.");return 1;}
        System.out.println("Previous backends stopped; safety records retained. Writes remain blocked pending review.");
      }
      if (connected(provider)) { System.out.println("8bercue is already connected. Return to the app to check card access."); return 0; }
      Bundle challenge = call(provider, CompanionBinderProvider.CHALLENGE_METHOD, new Bundle());
      if (challenge == null || challenge.getInt("protocol") != CompanionBinderProvider.MANAGEMENT_VERSION
          || challenge.getInt("versionCode") != BuildConfig.VERSION_CODE) throw new SecurityException("App version mismatch");
      if (!OperationTokens.isValid(challenge.getString("nonce"))) throw new SecurityException("Invalid bootstrap challenge");
      feedback(provider, challenge, "STARTING");
      System.out.println("Connecting to 8bercue…"); System.out.flush();
      File log = File.createTempFile("8bercue-connect-", ".log", new File("/data/local/tmp"));
      // Unique file, shell-only access. Never print challenges, identifiers or raw exceptions.
      if (!log.setReadable(false, false) || !log.setWritable(false, false)
          || !log.setReadable(true, true) || !log.setWritable(true, true)) {
        log.delete(); throw new SecurityException("Private bootstrap log unavailable");
      }
      try {
        boolean detach=new File("/system/bin/setsid").isFile();
        ProcessBuilder launch = detach
            ? new ProcessBuilder("/system/bin/setsid", "/system/bin/nohup", "/system/bin/app_process", "/",
                "--nice-name=8bercue-management-v1", "dev.sid.fiveberinspector.CompanionManagementStarter", "--connect-management")
            : new ProcessBuilder("/system/bin/nohup", "/system/bin/app_process", "/",
                "--nice-name=8bercue-management-v1", "dev.sid.fiveberinspector.CompanionManagementStarter", "--connect-management");
        launch.environment().put("CLASSPATH", apk);
        launch.redirectInput(new File("/dev/null")).redirectErrorStream(true).redirectOutput(log);
        Process child = launch.start();
        long until = android.os.SystemClock.elapsedRealtime() + 25000;
        while (android.os.SystemClock.elapsedRealtime() < until) {
          if (connected(provider)) {
            System.out.println("8bercue connected. Return to the app; card access is checked separately.");
            System.out.println("No profiles were changed by this command."); return 0;
          }
            if (!child.isAlive()) {
            // setsid returns after detaching. Exit 0 means the service left the USB session.
            if (child.exitValue()==0&&!bootstrapError(log)) { Thread.sleep(200); continue; }
            String output = new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8);
            String code = "START_FAILED";
            for (String line : output.split("\\R")) if (line.startsWith("8BERCUE_ERROR:")) {
              String candidate = line.substring("8BERCUE_ERROR:".length());
              if (ConnectionFeedback.allowed(candidate)) code = candidate;
            }
            // A parallel start may have succeeded while this child was rejected.
            if (connected(provider)) { System.out.println("8bercue is already connected. Return to the app."); return 0; }
            feedback(provider, challenge, code); System.err.println(ConnectionFeedback.message(code)); return 1;
          }
          Thread.sleep(200);
        }
        feedback(provider, challenge, "TIMEOUT"); System.err.println(ConnectionFeedback.message("TIMEOUT")); return 1;
      } catch (Exception failure) {
        feedback(provider, challenge, ConnectionFeedback.code(failure)); throw failure;
      } finally { log.delete(); }
    }
  }
  private static boolean bootstrapError(File log) {
    try {
      String output = new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8);
      for (String line : output.split("\\R")) {
        if (!line.startsWith("8BERCUE_ERROR:")) continue;
        String candidate = line.substring("8BERCUE_ERROR:".length());
        if (ConnectionFeedback.allowed(candidate)) return true;
      }
    } catch (Exception ignored) { /* Log may be unreadable while the child is still exiting. */ }
    return false;
  }
  private Connect() {}
}
