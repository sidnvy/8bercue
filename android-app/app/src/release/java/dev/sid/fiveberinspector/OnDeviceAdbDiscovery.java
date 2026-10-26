package dev.sid.fiveberinspector;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Discovers the phone's own modern wireless ADB endpoints through DNS-SD.
 *
 * Pairing and authenticated connection are different services/ports; these
 * discoveries do not authorize a Shell session. No port scans, adbd control,
 * peer pairing, or user secret storage are performed by this component.
 */
public final class OnDeviceAdbDiscovery implements AutoCloseable {
    public static final String PAIRING_TYPE = "_adb-tls-pairing._tcp";
    public static final String CONNECT_TYPE = "_adb-tls-connect._tcp";

    public enum Purpose { PAIRING, CONNECTION }

    public static final class Endpoint {
        public final Purpose purpose;
        public final String address;
        public final int port;

        private Endpoint(Purpose purpose, String address, int port) {
            this.purpose = purpose;
            this.address = address;
            this.port = port;
        }
    }

    public interface Observer {
        void found(Endpoint endpoint);
        void lost(Purpose purpose);
        void failed(Purpose purpose);
    }

    private final Purpose purpose;
    private final Observer observer;
    private final NsdManager manager;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean discoveryStarted;
    private final android.os.Handler resolveHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final int RESOLVE_RETRIES = 2;
    private volatile String discoveredService;
    private final java.util.concurrent.atomic.AtomicLong generation = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.ConcurrentMap<String,Long> resolutions = new java.util.concurrent.ConcurrentHashMap<>();
    private final NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
        @Override public void onDiscoveryStarted(String type) {
            android.util.Log.i("EightberAdbNsd", "started type=" + type);
            discoveryStarted = true;
            // A sheet can be closed before this asynchronous callback arrives.
            if (!running.get()) stopRegisteredDiscovery();
        }
        @Override public void onServiceFound(NsdServiceInfo info) {
            if (!running.get()) return;
            android.util.Log.i("EightberAdbNsd", "found type=" + info.getServiceType());
            // Resolve only matching ADB DNS-SD services; never scan port ranges.
            if (!matchesServiceType(info.getServiceType(), purpose)) return;
            final Long ticket = generation.incrementAndGet();
            resolutions.put(info.getServiceName(),ticket);
            resolve(info,ticket,RESOLVE_RETRIES);
        }

        @Override public void onServiceLost(NsdServiceInfo info) {
            resolutions.remove(info.getServiceName());
            if (running.get() && Objects.equals(discoveredService, info.getServiceName())) {
                discoveredService = null;
                observer.lost(purpose);
            }
        }
        @Override public void onDiscoveryStopped(String type) { discoveryStarted = false; }
        @Override public void onStartDiscoveryFailed(String type, int code) {
            android.util.Log.w("EightberAdbNsd", "discovery failed=" + code);
            discoveryStarted = false;
            if (running.get()) observer.failed(purpose);
        }
        @Override public void onStopDiscoveryFailed(String type, int code) { discoveryStarted = false; }
    };

    private boolean isCurrent(NsdServiceInfo info,Long ticket) {
        return running.get() && ticket.equals(resolutions.get(info.getServiceName()));
    }

    private void resolve(NsdServiceInfo info,Long ticket,int retries) {
        if (!isCurrent(info,ticket)) return;
        try {
            manager.resolveService(info,new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo service,int code) {
                    if (!isCurrent(info,ticket)) return;
                    // Older NSD clients serialize resolutions. Retry only that
                    // transient condition, preserving this generation and budget.
                    if (code == NsdManager.FAILURE_ALREADY_ACTIVE && retries > 0) {
                        resolveHandler.postDelayed(()->resolve(info,ticket,retries-1),300);
                    } else resolutions.remove(info.getServiceName(),ticket);
                }
                @Override public void onServiceResolved(NsdServiceInfo service) {
                    if (!isCurrent(info,ticket)
                            || !Objects.equals(info.getServiceName(),service.getServiceName())
                            || !matchesServiceType(service.getServiceType(),purpose)) return;
                    // API 34+ can return several addresses. The deprecated
                    // first-host accessor may choose a foreign/stale address;
                    // never fall back to it when another resolved address is local.
                    InetAddress host=null;
                    int candidates=0;
                    if (android.os.Build.VERSION.SDK_INT >= 34) {
                        for (InetAddress candidate:service.getHostAddresses()) {
                            candidates++;
                            if (host==null && isOwnAddress(candidate)) host=candidate;
                        }
                    } else {
                        candidates=1;
                        InetAddress candidate=service.getHost();
                        if (candidate!=null && isOwnAddress(candidate)) host=candidate;
                    }
                    int port=service.getPort();
                    boolean local=host!=null && isOwnAddress(host);
                    android.util.Log.i("EightberAdbNsd", "resolved local="+local+" validPort="+(port>0&&port<=65535)+" candidates="+candidates);
                    if (!local || port<=0 || port>65535) return;
                    discoveredService=service.getServiceName();
                    observer.found(new Endpoint(purpose,host.getHostAddress(),port));
                }
            });
        } catch (RuntimeException ignored) {
            resolutions.remove(info.getServiceName(),ticket);
            // Failure never changes Android debugging/network configuration.
        }
    }

    public OnDeviceAdbDiscovery(Context context, Purpose purpose, Observer observer) {
        this.purpose = Objects.requireNonNull(purpose);
        this.observer = Objects.requireNonNull(observer);
        this.manager = Objects.requireNonNull(context.getApplicationContext().getSystemService(NsdManager.class));
    }

    public static boolean matchesServiceType(String type, Purpose purpose) {
        if (type == null || purpose == null) return false;
        // Android's discoverServices API expects an unqualified service type;
        // resolver callbacks may return that type with a trailing DNS dot.
        String normalized = type.endsWith(".") ? type.substring(0, type.length() - 1) : type;
        // Pixel Android 17 NSD resolveService() can prefix its service type
        // with a literal dot, even though discovery uses an unprefixed name.
        if (normalized.startsWith(".")) normalized = normalized.substring(1);
        return (purpose == Purpose.PAIRING ? PAIRING_TYPE : CONNECT_TYPE).equalsIgnoreCase(normalized);
    }

    /** Require an interface address owned by this device; reject remote hosts. */
    static boolean isOwnAddress(InetAddress address) {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp()) continue;
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    if (address.equals(addresses.nextElement())) return true;
                }
            }
        } catch (Exception ignored) { }
        return false;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        try {
            manager.discoverServices(purpose == Purpose.PAIRING ? PAIRING_TYPE : CONNECT_TYPE,
                    NsdManager.PROTOCOL_DNS_SD, listener);
        } catch (RuntimeException failure) {
            running.set(false);
            observer.failed(purpose);
        }
    }

    private void stopRegisteredDiscovery() {
        if (!discoveryStarted) return;
        discoveryStarted = false;
        try { manager.stopServiceDiscovery(listener); }
        catch (RuntimeException ignored) { }
    }

    @Override public void close() {
        if (!running.getAndSet(false)) return;
        discoveredService = null;
        resolutions.clear();
        resolveHandler.removeCallbacksAndMessages(null);
        stopRegisteredDiscovery();
    }
}
