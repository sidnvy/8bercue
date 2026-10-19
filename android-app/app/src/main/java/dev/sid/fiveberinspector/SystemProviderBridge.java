package dev.sid.fiveberinspector;
import android.os.IBinder;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
final class SystemProviderBridge {
    static final class Connection implements AutoCloseable {
        final Object provider;
        private final Object manager;
        private final String authority;
        private final IBinder token;
        private boolean closed;
        Connection(Object provider,Object manager,String authority,IBinder token){this.provider=provider;this.manager=manager;this.authority=authority;this.token=token;}
        @Override public void close() throws Exception {
            if(closed)return;
            manager.getClass().getMethod("removeContentProviderExternalAsUser",String.class,IBinder.class,int.class)
                .invoke(manager,authority,token,0);
            closed=true;
        }
    }
    static Connection acquire(String authority) throws Exception {
        Object manager=Class.forName("android.app.ActivityManager").getDeclaredMethod("getService").invoke(null);
        IBinder token=new android.os.Binder();
        Object holder=manager.getClass().getMethod("getContentProviderExternal",String.class,int.class,IBinder.class,String.class)
            .invoke(manager,authority,0,token,authority);
        if(holder==null)throw new IllegalStateException("Missing provider holder");
        try {
            Object provider=holder.getClass().getField("provider").get(holder);
            if(provider==null)throw new IllegalStateException("Missing provider binder");
            return new Connection(provider,manager,authority,token);
        } catch(Exception failure) {
            new Connection(null,manager,authority,token).close();throw failure;
        }
    }
    static Object providerFromSystem(String authority) throws Exception {
        Class<?> activityManager = Class.forName("android.app.ActivityManager");
        Method getService = activityManager.getDeclaredMethod("getService");
        Object manager = getService.invoke(null);
        Method provider = manager.getClass().getMethod("getContentProviderExternal",
                String.class, int.class, IBinder.class, String.class);
        // Android ActivityManager returns ContentProviderHolder, not
        // IContentProvider. Unwrap its Binder-backed provider field first.
        Object holder = provider.invoke(manager, authority, 0, null, authority);
        if (holder == null) throw new IllegalStateException("Missing provider holder");
        Field field = holder.getClass().getField("provider");
        Object remote = field.get(holder);
        if (remote == null) throw new IllegalStateException("Missing provider binder");
        return remote;
    }

}
