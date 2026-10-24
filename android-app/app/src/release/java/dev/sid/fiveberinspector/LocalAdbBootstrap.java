package dev.sid.fiveberinspector;

import android.content.Context;
import android.os.Build;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bounded user-requested recovery over Android 11+ TLS wireless debugging.
 * Refuses classic TCP 5555 and will never modify adbd/debugging settings.
 * If the phone is not paired, direct the user to onboarding instead.
 */
public final class LocalAdbBootstrap {
    private LocalAdbBootstrap() {}
    public static final class Result {
        public final boolean ready;
        public final boolean needsAuthorization;
        public final String description;
        Result(boolean ready,boolean needsAuthorization,String description) {
            this.ready=ready;this.needsAuthorization=needsAuthorization;this.description=description;
        }
    }
    public static Result start(Context context) {
        if(Build.VERSION.SDK_INT < 31)
            return new Result(false,false,"This build requires Android 12+ TLS wireless debugging");
        if(CompanionBinderProvider.management()!=null)
            return new Result(true,false,"Authenticated management Binder already running");
        if(!OnDeviceAdbWirelessManager.hasPaired(context))
            return new Result(false,true,"Pair this phone once in Settings → Wireless ADB setup, then try again");
        CountDownLatch discovered = new CountDownLatch(1);
        AtomicReference<OnDeviceAdbDiscovery.Endpoint> endpoint = new AtomicReference<>();
        OnDeviceAdbDiscovery discovery = new OnDeviceAdbDiscovery(context,
                OnDeviceAdbDiscovery.Purpose.CONNECTION,new OnDeviceAdbDiscovery.Observer() {
            @Override public void found(OnDeviceAdbDiscovery.Endpoint e) {
                endpoint.set(e);discovered.countDown();
            }
            @Override public void lost(OnDeviceAdbDiscovery.Purpose p) {endpoint.set(null);}
            @Override public void failed(OnDeviceAdbDiscovery.Purpose p) {discovered.countDown();}
        });
        try {
            discovery.start();
            if(!discovered.await(8,TimeUnit.SECONDS) || endpoint.get()==null)
                return new Result(false,false,"Wireless debugging connection service not found; check Android Developer options");
            OnDeviceAdbWirelessManager.startManagement(context,endpoint.get());
            return new Result(CompanionBinderProvider.management()!=null,false,
                    "Authenticated local Wireless ADB management Binder connected");
        } catch(InterruptedException stop) {
            Thread.currentThread().interrupt();
            return new Result(false,false,"Connection check interrupted");
        } catch(Exception failure) {
            android.util.Log.w("EightberAdbRecovery", "connection_failed class=" + failure.getClass().getSimpleName());
            return new Result(false,false,"Wireless ADB management connection failed; retry from Settings");
        } finally {discovery.close();}
    }
}
