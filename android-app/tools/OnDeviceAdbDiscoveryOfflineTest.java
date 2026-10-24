package dev.sid.fiveberinspector;

import java.net.InetAddress;

/** Pure NSD filtering contract; requires only android.jar for unused Android types. */
public final class OnDeviceAdbDiscoveryOfflineTest {
    static int checked;
    static void ok(boolean pass, String label) {
        if (!pass) throw new AssertionError(label);
        checked++;
    }
    public static void main(String[] args) throws Exception {
        OnDeviceAdbDiscovery.Purpose p = OnDeviceAdbDiscovery.Purpose.PAIRING;
        OnDeviceAdbDiscovery.Purpose c = OnDeviceAdbDiscovery.Purpose.CONNECTION;
        ok(OnDeviceAdbDiscovery.matchesServiceType("_adb-tls-pairing._tcp.", p), "pairing exact");
        ok(OnDeviceAdbDiscovery.matchesServiceType("_adb-tls-pairing._tcp", p), "pairing no dot");
        ok(OnDeviceAdbDiscovery.matchesServiceType("_ADB-TLS-PAIRING._TCP.", p), "DNS-SD case insensitive");
        ok(OnDeviceAdbDiscovery.matchesServiceType("_adb-tls-connect._tcp.", c), "connect exact");
        ok(OnDeviceAdbDiscovery.matchesServiceType("._adb-tls-connect._tcp", c), "Pixel Android 17 resolved leading dot");
        ok(OnDeviceAdbDiscovery.matchesServiceType("._adb-tls-pairing._tcp", p), "Pixel Android 17 pairing leading dot");
        ok(!OnDeviceAdbDiscovery.matchesServiceType("_adb-tls-connect._tcp.", p), "never mix paired/connect");
        ok(!OnDeviceAdbDiscovery.matchesServiceType("_adb-tls-pairing._tcp.", c), "never connect to pairing port");
        ok(!OnDeviceAdbDiscovery.matchesServiceType("_adb-tls-connect._tcp.evil.example", c), "no suffix ambiguity");
        ok(!OnDeviceAdbDiscovery.matchesServiceType("_ssh._tcp.", c), "reject unrelated endpoints");
        ok(!OnDeviceAdbDiscovery.matchesServiceType(null, p), "null type rejected");
        ok(!OnDeviceAdbDiscovery.matchesServiceType("_adb-tls-connect._tcp.", null), "null purpose rejected");
        ok(OnDeviceAdbDiscovery.isOwnAddress(InetAddress.getByName("127.0.0.1")), "loopback is own address");
        ok(!OnDeviceAdbDiscovery.isOwnAddress(InetAddress.getByName("203.0.113.34")), "foreign network address rejected");
        System.out.println("PASS " + checked + " ADB DNS-SD and local-host filtering assertions");
    }
}
