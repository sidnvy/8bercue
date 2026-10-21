package dev.sid.fiveberinspector;

import android.content.Context;
import android.util.Base64;
import com.tananaev.adblib.AdbBase64;
import com.tananaev.adblib.AdbConnection;
import com.tananaev.adblib.AdbCrypto;
import com.tananaev.adblib.AdbStream;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.TimeUnit;

/**
 * One-time Shell companion bootstrap via the existing localhost TCP ADB daemon.
 *
 * Only connects to an already-running 127.0.0.1:5555 classic ADB listener.
 * Never changes wireless debugging, the adbd process, the TCP port, or the
 * workstation connection. Dynamic-port TLS wireless ADB is not supported.
 * Generates a dedicated, persisted ADB RSA keypair in private no-backup storage.
 * User must approve the Android "Allow USB debugging?" RSA prompt once.
 *
 * This is NOT intended to be a general-purpose ADB console. Only starts the
 * fixed, read-only ShellCompanion class from this very APK.
 */
public final class LocalAdbBootstrap {
    public static final String HOST = "127.0.0.1";
    public static final int PORT = 5555;
    private static final String KEY_PRIVATE = "local_adb_bootstrap.pk8";
    private static final String KEY_PUBLIC = "local_adb_bootstrap.pub";

    public static final class Result {
        public final boolean ready;
        public final boolean needsAuthorization;
        public final String description;
        Result(boolean ready, boolean needsAuthorization, String description) {
            this.ready = ready;
            this.needsAuthorization = needsAuthorization;
            this.description = description;
        }
    }

    private LocalAdbBootstrap() {}

    private static AdbCrypto crypto(Context app) throws Exception {
        File dir = app.getNoBackupFilesDir();
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Private key folder unavailable");
        File keyPrivate = new File(dir, KEY_PRIVATE);
        File keyPublic = new File(dir, KEY_PUBLIC);
        AdbBase64 encoder = data -> Base64.encodeToString(data, Base64.NO_WRAP);

        if (keyPrivate.isFile() && keyPublic.isFile()) {
            return AdbCrypto.loadAdbKeyPair(encoder, keyPrivate, keyPublic);
        }
        if (keyPrivate.exists() || keyPublic.exists())
            throw new IOException("Incomplete ADB keypair; refusing silent key replacement");
        AdbCrypto generated = AdbCrypto.generateAdbKeyPair(encoder);
        generated.saveAdbKeyPair(keyPrivate, keyPublic);
        // Both files are only accessible to this Android application UID.
        keyPrivate.setReadable(false, false);
        keyPrivate.setWritable(false, false);
        keyPrivate.setReadable(true, true);
        keyPrivate.setWritable(true, true);
        keyPublic.setReadable(false, false);
        keyPublic.setWritable(false, false);
        keyPublic.setReadable(true, true);
        keyPublic.setWritable(true, true);
        return generated;
    }

    private static String quote(String path) {
        // APK paths must come from the Android PackageManager; simple safe
        // characters only to avoid shell metacharacters or injection.
        if (!path.matches("/data/app/[A-Za-z0-9_~./+=-]+/base\\.apk"))
            throw new SecurityException("Unexpected installed application path");
        return path;
    }

    private static String oneFixedCommand(Context ctx) {
        int appUid = ctx.getApplicationInfo().uid;
        if (appUid < 10000 || appUid > 199999)
            throw new SecurityException("Unexpected installed app UID");
        String apk = quote(ctx.getApplicationInfo().sourceDir);
        String cmd =
            "CLASSPATH=" + apk + " " +
            "/system/bin/app_process /system/bin " +
            "dev.sid.fiveberinspector.ShellCompanion " +
            "--app-uid " + appUid;
        // The helper is detached from the ADB session and logs only non-secret
        // diagnostics. No Android profile action is performed during bootstrap.
        return "/system/bin/setsid /system/bin/sh -c " +
            "'" + cmd + " </dev/null >/data/local/tmp/fiveber-inspector-helper.log 2>&1'" +
            " </dev/null >/dev/null 2>&1 & /system/bin/sleep 1; /system/bin/echo FIVEBER_LAUNCH_SENT";
    }

    /**
     * Attempt self-hosted shell startup. Do not call from the UI thread.
     * User has to explicitly accept the system's ADB RSA authorization prompt.
     */
    public static Result start(Context context) throws Exception {
        Context app = context.getApplicationContext();
        Socket socket = new Socket();
        AdbConnection connection = null;
        try {
            socket.connect(new InetSocketAddress(HOST, PORT), 3000);
            socket.setTcpNoDelay(true);
            connection = AdbConnection.create(socket, crypto(app));
            boolean connected = connection.connect(25, TimeUnit.SECONDS, false);
            if (!connected) {
                return new Result(false, true,
                    "等待 Pixel 的 ADB RSA 授权。请选择 Always allow，然后重试。");
            }
            // Do not launch a second daemon while the socket is already bound.
            try {
                String status = ShellCompanionClient.status();
                return new Result(true, false,
                    "本机 ADB 已授权；当前 Shell Companion 已在运行：" + status);
            } catch (Exception ignored) {
                // Companion is absent: proceed with the one fixed launch command.
            }
            String shell = oneFixedCommand(app);
            AdbStream stream = connection.open("shell:" + shell);
            // The ADB shell launches the helper in a detached session.
            // Avoid waiting on a long-running user service in this stream.
            try {
                Thread.sleep(1800);
            } finally {
                stream.close();
            }
            try {
                String status = ShellCompanionClient.status();
                return new Result(true, false, status);
            } catch (Exception ignored) {
                return new Result(false, false,
                    "ADB 授权成功，但 Shell Companion 尚未就绪，请稍后重试。");
            }
        } catch (java.net.ConnectException error) {
            return new Result(false, false,
                "手机的 127.0.0.1:5555 未开放；请检查 ADB TCP 服务。");
        } finally {
            if (connection != null) {
                try { connection.close(); } catch (IOException ignored) {}
            } else {
                try { socket.close(); } catch (IOException ignored) {}
            }
        }
    }
}
