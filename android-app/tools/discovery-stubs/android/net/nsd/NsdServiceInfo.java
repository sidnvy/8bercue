package android.net.nsd;import java.net.InetAddress;
public class NsdServiceInfo {
 public java.util.List<InetAddress> addresses;
 public java.util.List<InetAddress> getHostAddresses(){return addresses!=null?addresses:java.util.Collections.singletonList(host);}
 public String name="local",type="_adb-tls-connect._tcp";public InetAddress host;public int port;
 public String getServiceName(){return name;}public String getServiceType(){return type;}
 public InetAddress getHost(){return host;}public int getPort(){return port;}
}
