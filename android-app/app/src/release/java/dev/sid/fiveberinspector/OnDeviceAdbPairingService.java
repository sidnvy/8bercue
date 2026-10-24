package dev.sid.fiveberinspector;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import android.provider.Settings;
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
    private static final String CODE_KEY="pairing_code";
    private static final int NOTICE_ID=4931;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private OnDeviceAdbDiscovery pairingSearch,connectSearch;
    private OnDeviceAdbDiscovery.Endpoint pairing,connection;
    private volatile boolean paired,working;
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
        } else if(CODE.equals(action) && started){
            android.os.Bundle input=RemoteInput.getResultsFromIntent(intent);
            CharSequence value=input==null?null:input.getCharSequence(CODE_KEY);
            if(value==null)return START_NOT_STICKY;
            // Avoid an additional immutable String containing the ephemeral code.
            char[] code=new char[value.length()];
            for(int i=0;i<code.length;i++)code[i]=value.charAt(i);
            if(!OnDeviceAdbPairingExchange.validCode(code)) {
                Arrays.fill(code,'\0');
                update("Invalid pairing code","Open Pair device with pairing code, then enter six digits");
                return START_NOT_STICKY;
            }
            final OnDeviceAdbDiscovery.Endpoint target=pairing;
            if(target==null){Arrays.fill(code,'\0');update("Pairing not found","Keep the Android pairing-code screen open");return START_NOT_STICKY;}
            if(working){Arrays.fill(code,'\0');return START_NOT_STICKY;}
            working=true;
            update("8bercue · Pairing","Authenticating to this device…");
            io.execute(()->{
                try {
                    OnDeviceAdbWirelessManager.pair(this,target,code);
                    paired=true;
                    ui.post(()->{
                        if(!started)return;
                        update("8bercue · Paired","Launching the verified management service…");
                    });
                } catch(Exception error) {
                    ui.post(()->{
                        if(started){
                            update("Pairing failed","Check the code and retry from Android Wireless debugging");
                            pairingNotification(); // Keep notification code entry available.
                        }
                    });
                } finally {
                    Arrays.fill(code,'\0');
                    working=false;
                    ui.post(()->{if(started && paired)launchIfReady();});
                }
            });
        }
        return START_NOT_STICKY;
    }
    private void beginDiscovery(){
        if(pairingSearch!=null)return;
        pairingSearch=new OnDeviceAdbDiscovery(this,OnDeviceAdbDiscovery.Purpose.PAIRING,
                new OnDeviceAdbDiscovery.Observer(){
                    @Override public void found(OnDeviceAdbDiscovery.Endpoint e){ui.post(()->{if(!started)return;pairing=e;pairingNotification();});}
                    @Override public void lost(OnDeviceAdbDiscovery.Purpose p){ui.post(()->{pairing=null;if(started&&!paired)update("Pairing service closed","Reopen Pair device with pairing code");});}
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
    private void pairingNotification(){
        if(pairing==null || paired)return;
        Intent codeIntent=new Intent(this,OnDeviceAdbPairingService.class).setAction(CODE);
        PendingIntent reply=PendingIntent.getForegroundService(this,5,codeIntent,
                PendingIntent.FLAG_MUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        RemoteInput input=new RemoteInput.Builder(CODE_KEY).setLabel("Six-digit pairing code").build();
        Notification.Action send=new Notification.Action.Builder(null,"Enter pairing code",reply)
                .addRemoteInput(input).build();
        Notification notice=base("8bercue · Pairing available","Enter the six-digit code shown by Android Settings")
                .addAction(send).build();
        getSystemService(NotificationManager.class).notify(NOTICE_ID,notice);
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
        if(pairingSearch!=null)pairingSearch.close();
        if(connectSearch!=null)connectSearch.close();
        io.shutdownNow();
        super.onDestroy();
    }
}
