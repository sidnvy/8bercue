package dev.sid.fiveberinspector;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import android.provider.Settings;
import android.widget.RemoteViews;
import java.util.concurrent.*;
import java.util.Arrays;

/**
 * User-initiated foreground wireless debugging onboarding.
 * The pairing code is accepted through an explicit, private notification reply;
 * no pairing code, device identifiers or service command output are logged.
 */
public final class OnDeviceAdbPairingService extends Service {
    private static final String CHANNEL="8bercue_local_adb_pairing";
    private static final String START="dev.sid.fiveberinspector.action.PAIRING_START";
    private static final String CODE="dev.sid.fiveberinspector.action.PAIRING_CODE";
    private static final String STOP="dev.sid.fiveberinspector.action.PAIRING_STOP";
    private static final String DIGIT="dev.sid.fiveberinspector.action.PAIRING_DIGIT";
    private static final String DELETE="dev.sid.fiveberinspector.action.PAIRING_DELETE";
    private static final String SUBMIT="dev.sid.fiveberinspector.action.PAIRING_SUBMIT";
    private static final String DIGIT_KEY="digit";
    private static final String CODE_KEY="pairing_code";
    private static final int NOTICE_ID=4931;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private OnDeviceAdbDiscovery pairingSearch,connectSearch;
    private OnDeviceAdbDiscovery.Endpoint pairing,connection;
    private volatile boolean paired,working;
    private final OnDeviceAdbCodeBuffer keypad = new OnDeviceAdbCodeBuffer();
    private boolean started;

    public static Intent startIntent(Context context) {
        return new Intent(context,OnDeviceAdbPairingService.class).setAction(START);
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onCreate(){
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(
                CHANNEL,"8bercue wireless debugging",NotificationManager.IMPORTANCE_HIGH));
    }
    private Notification.Builder base(String title,String description) {
        Notification.Builder b=new Notification.Builder(this,CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(title).setContentText(description)
                .setOngoing(true).setOnlyAlertOnce(true);
        Intent cancel=new Intent(this,OnDeviceAdbPairingService.class).setAction(STOP);
        PendingIntent stop=PendingIntent.getService(this,3,cancel,
                PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        b.addAction(new Notification.Action.Builder(null,"Cancel",stop).build());
        return b;
    }
    private void update(String title,String detail) {
        getSystemService(NotificationManager.class).notify(NOTICE_ID,base(title,detail).build());
    }
    private void foreground() {
        if(started)return;
        Notification initial=base("8bercue · Wireless ADB","Waiting for Android pairing service").build();
        if(Build.VERSION.SDK_INT>=29)
            startForeground(NOTICE_ID,initial,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(NOTICE_ID,initial);
        started=true;
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null)return START_NOT_STICKY;
        String action=intent.getAction();
        if(STOP.equals(action)){stopSelf();return START_NOT_STICKY;}
        if(START.equals(action)){
            foreground();
            beginDiscovery();
        } else if(DIGIT.equals(action) && started && !paired && !working) {
            if (pairing != null && keypad.append(intent.getIntExtra(DIGIT_KEY, -1)))
                pairingNotification();
        } else if(DELETE.equals(action) && started && !paired && !working) {
            keypad.delete();
            pairingNotification();
        } else if(SUBMIT.equals(action) && started) {
            char[] code = keypad.take();
            if (code != null) submitCode(code);
        } else if(CODE.equals(action) && started) {
            // Compatibility with an earlier inline RemoteInput action; keypad
            // is preferred because some Android 17 devices never display IME.
            android.os.Bundle input = RemoteInput.getResultsFromIntent(intent);
            CharSequence value = input == null ? null : input.getCharSequence(CODE_KEY);
            if (value != null) {
                char[] code = new char[value.length()];
                for (int i = 0; i < code.length; i++) code[i] = value.charAt(i);
                keypad.clear();
                submitCode(code);
            }
        }
        return START_NOT_STICKY;
    }

    private void submitCode(char[] code) {
        if (!OnDeviceAdbPairingExchange.validCode(code)) {
            Arrays.fill(code, '\0');
            pairingNotification();
            return;
        }
        final OnDeviceAdbDiscovery.Endpoint target = pairing;
        if (target == null || working || paired) {
            Arrays.fill(code, '\0');
            pairingNotification();
            return;
        }
        working = true;
        update("8bercue · Pairing", "Authenticating to this device…");
        io.execute(() -> {
            try {
                OnDeviceAdbWirelessManager.pair(this, target, code);
                paired = true;
                ui.post(() -> {
                    if (!started) return;
                    update("8bercue · Paired", "Launching the verified management service…");
                });
            } catch (Exception error) {
                ui.post(() -> {
                    if (started) {
                        // No code, digest, or device identifier in diagnostics.
                        pairingNotification();
                    }
                });
            } finally {
                Arrays.fill(code, '\0');
                working = false;
                ui.post(() -> { if (started && paired) launchIfReady(); });
            }
        });
    }

    private void beginDiscovery(){
        if(pairingSearch!=null)return;
        pairingSearch=new OnDeviceAdbDiscovery(this,OnDeviceAdbDiscovery.Purpose.PAIRING,
                new OnDeviceAdbDiscovery.Observer(){
                    @Override public void found(OnDeviceAdbDiscovery.Endpoint e){ui.post(()->{if(!started)return;pairing=e;pairingNotification();});}
                    @Override public void lost(OnDeviceAdbDiscovery.Purpose p){ui.post(()->{pairing=null;keypad.clear();if(started&&!paired)update("Pairing service closed","Reopen Pair device with pairing code");});}
                    @Override public void failed(OnDeviceAdbDiscovery.Purpose p){ui.post(()->{if(started)update("Pairing discovery unavailable","Open wireless debugging in Android settings");});}
                });
        connectSearch=new OnDeviceAdbDiscovery(this,OnDeviceAdbDiscovery.Purpose.CONNECTION,
                new OnDeviceAdbDiscovery.Observer(){
                    @Override public void found(OnDeviceAdbDiscovery.Endpoint e){ui.post(()->{if(!started)return;connection=e;launchIfReady();});}
                    @Override public void lost(OnDeviceAdbDiscovery.Purpose p){ui.post(()->{connection=null;if(started&&paired)update("Connection port changed","Waiting for this phone's wireless debugging service…");});}
                    @Override public void failed(OnDeviceAdbDiscovery.Purpose p){ui.post(()->{if(started)update("Connection discovery unavailable","Keep Wireless debugging enabled");});}
                });
        pairingSearch.start();connectSearch.start();
    }
    private PendingIntent digitAction(int digit) {
        Intent action = new Intent(this, OnDeviceAdbPairingService.class)
                .setAction(DIGIT).putExtra(DIGIT_KEY, digit);
        return PendingIntent.getService(this, 130 + digit, action,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent keypadAction(String actionName, int requestCode) {
        Intent action = new Intent(this, OnDeviceAdbPairingService.class).setAction(actionName);
        return PendingIntent.getService(this, requestCode, action,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /**
     * Android 17's notification RemoteInput can expose a focused inline field
     * without ever showing the IME. RemoteViews digit buttons need no keyboard,
     * do not leave the system pairing dialog, and never render entered digits.
     */
    private void pairingNotification() {
        if (!started || pairing == null || paired || working) return;
        RemoteViews pad = new RemoteViews(getPackageName(), R.layout.notification_adb_pair_keypad);
        int[] digitViews = { R.id.pair_digit_0, R.id.pair_digit_1,
                R.id.pair_digit_2, R.id.pair_digit_3, R.id.pair_digit_4,
                R.id.pair_digit_5, R.id.pair_digit_6, R.id.pair_digit_7,
                R.id.pair_digit_8, R.id.pair_digit_9 };
        for (int digit = 0; digit < digitViews.length; digit++) {
            pad.setOnClickPendingIntent(digitViews[digit], digitAction(digit));
        }
        pad.setOnClickPendingIntent(R.id.pair_delete, keypadAction(DELETE, 141));
        pad.setOnClickPendingIntent(R.id.pair_submit, keypadAction(SUBMIT, 142));
        pad.setTextViewText(R.id.pair_digit_status,
                getString(R.string.wireless_adb_keypad_hint) + "  " + keypad.masked());
        // Noninteractive until exactly six digits are present.
        pad.setViewVisibility(R.id.pair_submit,
                keypad.complete() ? android.view.View.VISIBLE : android.view.View.INVISIBLE);
        Notification notice = base("8bercue · Pairing available", "Expand this notification to enter digits without a keyboard")
                .setStyle(new Notification.DecoratedCustomViewStyle())
                .setCustomBigContentView(pad)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setLocalOnly(true)
                .build();
        getSystemService(NotificationManager.class).notify(NOTICE_ID, notice);
    }

    private void launchIfReady(){
        if(!paired || connection==null || working || !started)return;
        working=true;
        OnDeviceAdbDiscovery.Endpoint endpoint=connection;
        io.execute(()->{
            try {
                OnDeviceAdbWirelessManager.startManagement(this,endpoint);
                ui.post(()->{
                    if(!started)return;
                    update("8bercue · Connected","Authenticated management service ready");
                    stopSelf();
                });
            } catch(Exception failure) {
                ui.post(()->{if(started)update("Management not ready","Open 8bercue to inspect the connection result");});
            } finally {
                working=false;
                // A failed Binder handoff must not automatically retry the
                // fixed command; require the user to initiate recovery.
            }
        });
    }
    @Override public void onDestroy(){
        started=false;
        keypad.clear();
        if(pairingSearch!=null)pairingSearch.close();
        if(connectSearch!=null)connectSearch.close();
        io.shutdownNow();
        super.onDestroy();
    }
}
