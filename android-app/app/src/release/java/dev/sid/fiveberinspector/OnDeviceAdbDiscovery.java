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
    private volatile String discoveredService;
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
            try {
                manager.resolveService(info, new NsdManager.ResolveListener() {
                    @Override public void onResolveFailed(NsdServiceInfo service, int code) { }
                    @Override public void onServiceResolved(NsdServiceInfo service) {
                        android.util.Log.i("EightberAdbNsd", "resolved type=" + service.getServiceType());
                        if (!running.get() || !matchesServiceType(service.getServiceType(), purpose)) return;
                        InetAddress host = service.getHost();
                        int port = service.getPort();
                        android.util.Log.i("EightberAdbNsd", "resolved local=" +
                                (host != null && isOwnAddress(host)) + " validPort=" + (port > 0 && port <= 65535));
                        if (port <= 0 || port > 65535 || host == null || !isOwnAddress(host)) return;
                        discoveredService = service.getServiceName();
                        observer.found(new Endpoint(purpose, host.getHostAddress(), port));
                    }
                });
            } catch (RuntimeException ignored) {
                // A concurrent discovery/resolve or disabled Wi-Fi is not a reason
                // to modify the device's debug settings.
            }
        }
        @Override public void onServiceLost(NsdServiceInfo info) {
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
        stopRegisteredDiscovery();
    }
}
