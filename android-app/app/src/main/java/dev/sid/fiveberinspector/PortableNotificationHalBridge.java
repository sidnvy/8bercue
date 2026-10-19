package dev.sid.fiveberinspector;
import android.os.Process;
import dalvik.system.PathClassLoader;
/** No caller-supplied APDU: only exact notification retrieve and acknowledged removal. */
final class PortableNotificationHalBridge implements AutoCloseable {
  private final String reader,hash;
  private final NotificationSessionPolicy policy;
  private Object hal;private Class<?> api;private int channel=-1;
  PortableNotificationHalBridge(String reader,String hash,long sequence){this.reader=reader;this.hash=hash;policy=new NotificationSessionPolicy(sequence);}
  private void open() throws Exception {
    if(channel>0)return;
    if(Process.myUid()!=Process.SHELL_UID)throw new SecurityException("Shell identity required");
    FiveberReaderSelector.Target card=FiveberReaderSelector.discover();
    if(!reader.equals(card.reader)||!hash.equals(card.identityHash))throw new SecurityException("Notification card changed");
    Class<?> binderType=Class.forName("android.os.IBinder");
    Object binder=Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"android.hardware.secure_element.ISecureElement/"+reader);
    if(binder==null)throw new SecurityException("Notification reader unavailable");
    ClassLoader loader=new PathClassLoader(SystemHalAdapter.dexPath(),getClass().getClassLoader());
    api=Class.forName("android.hardware.secure_element.ISecureElement",true,loader);
    hal=Class.forName("android.hardware.secure_element.ISecureElement$Stub",true,loader).getMethod("asInterface",binderType).invoke(null,binder);
    if(!(Boolean)api.getMethod("isCardPresent").invoke(hal))throw new SecurityException("Card unavailable");
    Object result=api.getMethod("openLogicalChannel",byte[].class,byte.class).invoke(hal,PortableDownloadHalBridge.unhex("A0000005591010FFFFFFFF8900000100"),(byte)0);
    channel=result.getClass().getField("channelNumber").getInt(result);
    if(channel<1||channel>3)throw new SecurityException("Invalid notification channel");
  }
  byte[] retrieve() throws Exception {open();return exchange();}
  byte[] removeAfterAcknowledgement() throws Exception {
    DownloadWorkerSupervisor.checkCancelled();
    FiveberReaderSelector.Target current=FiveberReaderSelector.discover();
    if(!reader.equals(current.reader)||!hash.equals(current.identityHash))throw new SecurityException("Notification card changed before cleanup");
    policy.acknowledged();return exchange();
  }
  private byte[] exchange() throws Exception {
    for(;;) {
      DownloadWorkerSupervisor.checkCancelled();byte[] command=policy.next(channel);
      byte[] response=(byte[])api.getMethod("transmit",byte[].class).invoke(hal,(Object)command);
      DownloadWorkerSupervisor.checkCancelled();byte[] complete=policy.accept(response);if(complete!=null)return complete;
    }
  }
  @Override public void close(){if(channel>0&&api!=null&&hal!=null)try{api.getMethod("closeChannel",byte.class).invoke(hal,(byte)channel);}catch(Exception ignored){}channel=-1;}
}
