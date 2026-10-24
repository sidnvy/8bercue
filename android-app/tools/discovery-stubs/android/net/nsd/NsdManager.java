package android.net.nsd;import java.util.*;
public class NsdManager {
 public static final int PROTOCOL_DNS_SD=1;public DiscoveryListener listener;
 public List<ResolveListener> resolves=new ArrayList<>();public int stops;
 public interface DiscoveryListener {
 void onDiscoveryStarted(String t);void onServiceFound(NsdServiceInfo i);void onServiceLost(NsdServiceInfo i);
 void onDiscoveryStopped(String t);void onStartDiscoveryFailed(String t,int c);void onStopDiscoveryFailed(String t,int c);
 }
 public interface ResolveListener {void onResolveFailed(NsdServiceInfo i,int c);void onServiceResolved(NsdServiceInfo i);}
 public void discoverServices(String t,int p,DiscoveryListener l){listener=l;}
 public void stopServiceDiscovery(DiscoveryListener l){stops++;}
 public void resolveService(NsdServiceInfo i,ResolveListener l){resolves.add(l);}
}
