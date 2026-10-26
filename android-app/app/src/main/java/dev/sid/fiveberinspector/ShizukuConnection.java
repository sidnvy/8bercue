package dev.sid.fiveberinspector;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import rikka.shizuku.Shizuku;

/** Optional fixed bootstrap transport; it never replaces the authenticated management Binder. */
final class ShizukuConnection {
    static final int PERMISSION_REQUEST = 7313;

    static boolean available() {
        try { return Shizuku.pingBinder() && !Shizuku.isPreV11()
                && Shizuku.getVersion() >= 13 && Shizuku.getUid() == 2000; }
        catch (RuntimeException unavailable) { return false; }
    }
    static boolean permitted() {
        try { return available() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED; }
        catch (RuntimeException unavailable) { return false; }
    }

    /** Called only by the App's background job after its explicit local consent window opens. */
    static boolean connect(Context context) throws Exception {
        if (!permitted()) return false;
        Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
                new ComponentName(context, ShizukuBootstrapService.class))
                .tag("8bercue-fixed-bootstrap").processNameSuffix("bootstrap")
                .version(BuildConfig.VERSION_CODE).debuggable(false).daemon(false);
        AtomicReference<IBinder> service = new AtomicReference<>();
        CountDownLatch arrived = new CountDownLatch(1);
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                service.set(binder);arrived.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                service.set(null);arrived.countDown();
            }
        };
        try {
            Shizuku.bindUserService(args, connection);
            if (!arrived.await(15, TimeUnit.SECONDS) || !permitted()) return false;
            IBinder binder = service.get();
            if (binder == null || !binder.pingBinder()
                    || !IShizukuBootstrap.Stub.DESCRIPTOR.equals(binder.getInterfaceDescriptor())) return false;
            boolean completed = IShizukuBootstrap.Stub.asInterface(binder).connect();
            // A launch result is insufficient. Only the original Shell ingress can
            // install this management Binder using its current versioned App challenge.
            return completed && CompanionBinderProvider.management() != null;
        } finally {
            try { Shizuku.unbindUserService(args, connection, true); }
            catch (RuntimeException disconnected) { /* The one-use daemon=false launcher may already be gone. */ }
        }
    }

    private ShizukuConnection() {}
}
