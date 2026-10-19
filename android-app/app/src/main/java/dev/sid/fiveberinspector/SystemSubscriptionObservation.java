package dev.sid.fiveberinspector;

import android.os.Process;
import android.telephony.SubscriptionInfo;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Optional Android subscription identity observation, never a network-registration check. */
public final class SystemSubscriptionObservation {
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean();
    private static final long TIMEOUT_MS = 2000;
    private SystemSubscriptionObservation() {}

    /** The raw target and active ICCIDs stay in memory; only non-identifying states leave here. */
    public static JSONObject observe(String targetIccid) {
        if (Process.myUid() != Process.SHELL_UID || !validId(targetIccid))
            return result("unavailable", true);
        if (!IN_FLIGHT.compareAndSet(false, true))
            return result("unavailable", false);
        AtomicBoolean completed = new AtomicBoolean();
        FutureTask<String> task = new FutureTask<>(() -> {
            try { return classify(targetIccid, readActiveIccids()); }
            catch (Throwable unavailable) { return "unavailable"; }
        });
        // Cleanup belongs to the thread: a FutureTask cancelled before starting never
        // invokes its callable. A Binder call may also ignore interruption, so keep
        // the gate closed until the worker actually exits.
        Thread worker = new Thread(() -> {
            try { task.run(); }
            finally { completed.set(true); IN_FLIGHT.set(false); }
        }, "subscription-identity-read");
        worker.setDaemon(true);
        try {
            worker.start();
            return result(task.get(TIMEOUT_MS, TimeUnit.MILLISECONDS), true);
        } catch (InterruptedException interrupted) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            return result("unavailable", completed.get());
        } catch (Throwable unavailable) {
            task.cancel(true);
            if (worker.getState() == Thread.State.NEW) IN_FLIGHT.set(false);
            return result("unavailable", completed.get());
        }
    }

    private static List<String> readActiveIccids() throws Exception {
        Object binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String.class).invoke(null, "isub");
        if (binder == null) return null;
        Class<?> api = Class.forName("com.android.internal.telephony.ISub");
        Object service = Class.forName("com.android.internal.telephony.ISub$Stub")
            .getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, binder);
        if (service == null) return null;
        Object response;
        // Current AOSP ISub uses (callingPackage, callingFeatureId, isForAllProfiles).
        // The false flag observes active subscriptions visible to this caller, not other users.
        // Unsupported hidden APIs or redacted identifiers fail unavailable; no shell parsing.
        try {
            Method method = api.getMethod("getActiveSubscriptionInfoList", String.class,
                String.class, boolean.class);
            response = method.invoke(service, "com.android.shell", null, false);
        } catch (NoSuchMethodException olderPlatform) {
            Method method = api.getMethod("getActiveSubscriptionInfoList", String.class, String.class);
            response = method.invoke(service, "com.android.shell", null);
        }
        if (!(response instanceof List<?>)) return null;
        List<?> subscriptions = (List<?>) response;
        if (subscriptions.size() > 32) return null;
        List<String> ids = new ArrayList<>();
        for (Object subscription : subscriptions) {
            if (!(subscription instanceof SubscriptionInfo)) return null;
            ids.add(((SubscriptionInfo) subscription).getIccId());
        }
        return ids;
    }

    static String classify(String target, List<String> activeIds) {
        if (!validId(target) || activeIds == null || activeIds.size() > 32) return "unavailable";
        Set<String> seen = new HashSet<>();
        int matches = 0;
        for (String id : activeIds) {
            // Redaction prevents proving a unique match, even if another entry matched.
            if (!validId(id) || !seen.add(id)) return "unavailable";
            if (target.equals(id)) matches++;
        }
        return matches == 1 ? "matched" : "mismatch";
    }
    private static boolean validId(String id) {
        return id != null && id.matches("[0-9]{19,20}") && !id.matches("0+");
    }
    private static JSONObject result(String status, boolean stopped) {
        try {
            return new JSONObject().put("systemSubscriptionStatus", status)
                .put("systemSubscriptionWorkerStopped", stopped)
                .put("networkRegistrationChecked", false);
        } catch (Exception impossible) { throw new IllegalStateException("Observation encoding failed"); }
    }
}
