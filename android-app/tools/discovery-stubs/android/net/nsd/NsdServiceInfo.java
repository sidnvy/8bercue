package android.net.nsd;import java.net.InetAddress;
public class NsdServiceInfo {
 public String name="local",type="_adb-tls-connect._tcp";public InetAddress host;public int port;
 public String getServiceName(){return name;}public String getServiceType(){return type;}
 public InetAddress getHost(){return host;}public int getPort(){return port;}
}
