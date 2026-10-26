package dev.sid.fiveberinspector;

import android.os.IBinder;
import android.os.Process;
import android.telephony.UiccCardInfo;
import org.json.JSONObject;
import java.lang.reflect.Method;
import java.util.List;

/** Narrow UID-2000 metadata repair for the platform's built-in eUICC.
 * Never writes an eUICC profile, changes a port, or touches the external card.
 * The Android subscription database may later regress if the OS refreshes
 * another eUICC independently. Do not promise a permanent fix.
 */
final class SystemEsimVisibilityRepair {
    private SystemEsimVisibilityRepair() {}

    private static void requireShell() {
        if (Process.myUid() != Process.SHELL_UID) {
            throw new SecurityException("eSIM visibility refresh needs the authorized Shell Companion");
        }
    }

    private static Object remoteService(String name, String aidlStub) throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        IBinder binder = (IBinder) sm.getDeclaredMethod("getService", String.class)
                .invoke(null, name);
        if (binder == null) throw new IllegalStateException("Telephony service unavailable");
        Class<?> stub = Class.forName(aidlStub);
        return stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
    }

    private static int builtInCardId() throws Exception {
        requireShell();
        // UiccCardInfo card inventory is public only from Android 10 (API 29).
        // Fail closed on API 28 rather than touching any unspecified card.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q)
            throw new UnsupportedOperationException("Built-in eUICC enumeration requires Android 10+");
        Object phone = remoteService("phone", "com.android.internal.telephony.ITelephony$Stub");
        Method cardsMethod = Class.forName("com.android.internal.telephony.ITelephony")
                .getMethod("getUiccCardsInfo", String.class);
        Object reply = cardsMethod.invoke(phone, "com.android.shell");
        if (!(reply instanceof List)) throw new IllegalStateException("No UICC inventory");
        int found = -1;
        int count = 0;
        for (Object row : (List<?>) reply) {
            if (!(row instanceof UiccCardInfo)) throw new IllegalStateException("Unexpected UICC descriptor");
            UiccCardInfo card = (UiccCardInfo) row;
            if (card.isEuicc() && !card.isRemovable()) {
                if (card.getCardId() < 0) throw new IllegalStateException("Internal eUICC Card ID unavailable");
                found = card.getCardId();
                count++;
            }
        }
        if (count != 1) throw new IllegalStateException("Expected exactly one built-in eUICC");
        return found;
    }

    static JSONObject preflight() throws Exception {
        int cardId = builtInCardId();
        return new JSONObject().put("supported", true)
                .put("target", "built_in_euicc")
                .put("cardId", cardId)
                .put("onlySubscriptionMetadata", true)
                .put("noProfileMutation", true);
    }

    static JSONObject requestRefresh() throws Exception {
        // Re-resolve every time; a card ID is never supplied by an untrusted app.
        int cardId = builtInCardId();
        Object subscription = remoteService("isub", "com.android.internal.telephony.ISub$Stub");
        Class.forName("com.android.internal.telephony.ISub")
                .getMethod("requestEmbeddedSubscriptionInfoListRefresh", int.class)
                .invoke(subscription, cardId);
        // This request is asynchronous. Do not claim to have restored Settings.
        return new JSONObject().put("status", "refresh_requested")
                .put("target", "built_in_euicc")
                .put("onlySubscriptionMetadata", true)
                .put("noProfileMutation", true);
    }
}
