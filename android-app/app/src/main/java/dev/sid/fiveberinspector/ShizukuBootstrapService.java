package dev.sid.fiveberinspector;

import android.os.Binder;
import android.os.Process;
import java.io.File;
import java.util.concurrent.TimeUnit;

/** Shizuku starts this one-use launcher as Shell; card work stays in the existing service. */
public final class ShizukuBootstrapService extends IShizukuBootstrap.Stub {
    private boolean consumed;

    public ShizukuBootstrapService() {
        try { ServiceCaller.pinInstallation(); }
        catch (Exception invalid) { throw new SecurityException("Invalid bootstrap installation", invalid); }
    }

    @Override public synchronized boolean connect() {
        try (ServiceCaller.ShellCall caller = ServiceCaller.asShell()) {
            if (consumed) return false;
            consumed = true;
            String apk = NativeLpacBundle.installedApk();
            if (!ConnectionCommand.validApk(apk)) throw new SecurityException("Invalid installed APK");
            // This exact installed entrypoint enforces owner user, a live App challenge,
            // version and APK digest before publishing the existing typed management Binder.
            ProcessBuilder launch = new ProcessBuilder("/system/bin/app_process", "/",
                    "dev.sid.fiveberinspector.Connect");
            launch.environment().put("CLASSPATH", apk);
            File sink = new File("/dev/null");
            launch.redirectInput(sink).redirectOutput(sink).redirectError(sink);
            java.lang.Process child = launch.start();
            try {
                return child.waitFor(40, TimeUnit.SECONDS) && child.exitValue() == 0;
            } finally {
                // Only the bootstrap client is owned here. Never kill its management
                // descendant or any card worker; their existing lifetime rules still apply.
                if (child.isAlive()) child.destroyForcibly();
            }
        } catch (Exception unavailable) { return false; }
    }

    @Override public void destroy() {
        // Shizuku itself delivers the reserved destroy transaction as Shell UID.
        // Root-started Shizuku is deliberately outside this backend's identity contract.
        if (Process.myUid() != Process.SHELL_UID) throw new SecurityException("Shell bootstrap required");
        if (Binder.getCallingUid() != Process.SHELL_UID) {
            try { ServiceCaller.requireApp(); }
            catch (Exception denied) { throw new SecurityException("Unauthorized bootstrap caller", denied); }
        }
        System.exit(0);
    }
}
