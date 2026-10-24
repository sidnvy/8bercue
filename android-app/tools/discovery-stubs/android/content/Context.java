package android.content;
public class Context {
 public android.net.nsd.NsdManager manager=new android.net.nsd.NsdManager();
 public Context getApplicationContext(){return this;}
 public <T> T getSystemService(Class<T> type){return type.cast(manager);}
}
