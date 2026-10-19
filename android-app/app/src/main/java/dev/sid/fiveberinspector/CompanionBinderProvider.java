package dev.sid.fiveberinspector;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;

/** Delivers a Shell-origin Binder object to this app, without Shizuku.
 * Shell calls are checked using the kernel-provided Binder calling UID.
 * Never accept arbitrary caller/package names or an insecure TCP broker.
 */
public final class CompanionBinderProvider extends ContentProvider {
    static final String METHOD = "publishReadOnlyBinder";
    static final String KEY = "readOnlyBinder";
    private static volatile IBinder current;

    static final String MANAGEMENT_METHOD="publishManagementBinder";
    static final String CHALLENGE_METHOD="getManagementChallenge";
    static final int MANAGEMENT_VERSION=1;
    private static IBinder management;
    private static String pendingNonce;
    private static long pendingUntil;
    private static final IBinder clientLifetime = new Binder();
    static synchronized void beginManagementBootstrap(){
        if(management!=null&&management.pingBinder())throw new SecurityException("Management session already exists");
        pendingNonce=OperationTokens.create();pendingUntil=android.os.SystemClock.elapsedRealtime()+120000;
    }
    static synchronized IProfileService management(){
        if(management==null||!management.pingBinder()){management=null;return null;}
        return IProfileService.Stub.asInterface(management);
    }
    private Bundle managementCall(String method,Bundle extras){
      synchronized(CompanionBinderProvider.class){
        if(pendingNonce==null||android.os.SystemClock.elapsedRealtime()>=pendingUntil)
            throw new SecurityException("Start connection from the app first");
        if(CHALLENGE_METHOD.equals(method)){
            Bundle result=new Bundle();result.putString("nonce",pendingNonce);
            result.putInt("protocol",MANAGEMENT_VERSION);result.putInt("versionCode",BuildConfig.VERSION_CODE);
            result.putBinder("clientLifetime",clientLifetime);return result;
        }
        if(extras==null||!pendingNonce.equals(extras.getString("nonce"))
            ||extras.getInt("protocol")!=MANAGEMENT_VERSION||extras.getInt("versionCode")!=BuildConfig.VERSION_CODE)
            throw new SecurityException("Bootstrap session mismatch");
        IBinder next=extras.getBinder("managementBinder");
        try {
            String expected=NativeLpacBundle.digest(new java.io.File(getContext().getApplicationInfo().sourceDir));
            if(!expected.equals(extras.getString("apkSha256")))throw new SecurityException("Companion artifact mismatch");
            if(next==null||!next.pingBinder()||!IProfileService.Stub.DESCRIPTOR.equals(next.getInterfaceDescriptor()))
                throw new SecurityException("Invalid management interface");
            if(management!=null&&management.pingBinder())throw new SecurityException("Existing management session");
            next.linkToDeath(()->{synchronized(CompanionBinderProvider.class){if(management==next)management=null;}},0);
            management=next;pendingNonce=null;pendingUntil=0;
            Bundle result=new Bundle();result.putBoolean("accepted",true);return result;
        }catch(Exception failure){throw new SecurityException("Management handoff rejected",failure);}
    }
    }
    static ICompanionProbe service() {
        IBinder b = current;
        if (b == null || !b.pingBinder()) { current = null; return null; }
        try {
            if (!ICompanionProbe.Stub.DESCRIPTOR.equals(b.getInterfaceDescriptor()))
                return null;
        } catch (Exception e) { return null; }
        return ICompanionProbe.Stub.asInterface(b);
    }
    @Override public boolean onCreate() { android.util.Log.i("EightberBinderPilot", "provider_created"); return true; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        android.util.Log.i("EightberBinderPilot", "provider_call_arrived shellUid="+(Binder.getCallingUid()==Process.SHELL_UID));
        if(Binder.getCallingUid()==Process.SHELL_UID
                &&(MANAGEMENT_METHOD.equals(method)||CHALLENGE_METHOD.equals(method)))return managementCall(method,extras);
        if (Binder.getCallingUid() != Process.SHELL_UID || !METHOD.equals(method)
                || extras == null) throw new SecurityException("Shell Binder ingress only");
        IBinder b = extras.getBinder(KEY);
        if (b == null || !b.pingBinder()) throw new SecurityException("Missing service");
        try {
            if (!ICompanionProbe.Stub.DESCRIPTOR.equals(b.getInterfaceDescriptor()))
                throw new SecurityException("Wrong service interface");
        } catch (android.os.RemoteException e) { throw new SecurityException("Invalid Binder"); }
        current = b;
        Bundle result = new Bundle(); result.putBoolean("accepted", true); return result;
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sortOrder){return null;}
    @Override public String getType(Uri uri){return null;}
    @Override public Uri insert(Uri uri,ContentValues values){return null;}
    @Override public int delete(Uri uri,String where,String[] args){return 0;}
    @Override public int update(Uri uri,ContentValues values,String where,String[] args){return 0;}
}
