package dev.sid.fiveberinspector;
import android.content.Context;import android.net.nsd.*;import java.net.*;
public class OnDeviceAdbDiscoveryLifecycleTest {
 static int checks,found,lost,port;static void ok(boolean b){if(!b)throw new AssertionError();checks++;}
 public static void main(String[] args)throws Exception {
  Context c=new Context();NsdManager m=c.manager;
  OnDeviceAdbDiscovery d=new OnDeviceAdbDiscovery(c,OnDeviceAdbDiscovery.Purpose.CONNECTION,new OnDeviceAdbDiscovery.Observer(){
   public void found(OnDeviceAdbDiscovery.Endpoint e){found++;port=e.port;}
   public void lost(OnDeviceAdbDiscovery.Purpose p){lost++;}
   public void failed(OnDeviceAdbDiscovery.Purpose p){}
  });
  NsdServiceInfo info=new NsdServiceInfo();info.host=InetAddress.getByName("127.0.0.1");info.port=40001;
  d.start();m.listener.onDiscoveryStarted(info.type);
  m.listener.onServiceFound(info);m.listener.onServiceLost(info);m.resolves.get(0).onServiceResolved(info);
  ok(found==0); // Lost before resolve: never resurrect a vanished endpoint.
  m.listener.onServiceFound(info);m.resolves.get(1).onServiceResolved(info);ok(found==1&&port==40001);
  m.listener.onServiceFound(info);info.port=40002;
  m.resolves.get(1).onServiceResolved(info);ok(found==1); // Older resolution generation.
  m.resolves.get(2).onServiceResolved(info);ok(found==2&&port==40002);
  m.listener.onServiceLost(info);ok(lost==1);
  m.listener.onServiceFound(info);info.host=InetAddress.getByName("203.0.113.1");
  m.resolves.get(3).onServiceResolved(info);ok(found==2); // Never remote.
  info.host=InetAddress.getByName("127.0.0.1");info.port=0;
  m.listener.onServiceFound(info);m.resolves.get(4).onServiceResolved(info);ok(found==2);
  info.port=40003;m.listener.onServiceFound(info);d.close();m.resolves.get(5).onServiceResolved(info);ok(found==2&&m.stops==1);
  d=new OnDeviceAdbDiscovery(c,OnDeviceAdbDiscovery.Purpose.CONNECTION,new OnDeviceAdbDiscovery.Observer(){
   public void found(OnDeviceAdbDiscovery.Endpoint e){throw new AssertionError();}
   public void lost(OnDeviceAdbDiscovery.Purpose p){}public void failed(OnDeviceAdbDiscovery.Purpose p){}
  });
  d.start();d.close();m.listener.onDiscoveryStarted(info.type);ok(m.stops==2);
  System.out.println("PASS "+checks+" actual NSD lifecycle checks (simulated Android callbacks)");
 }
}
