package dev.sid.fiveberinspector;
import java.security.SecureRandom;
/** One wire representation shared by all transports and operation types. */
final class OperationTokens {
    private static final SecureRandom RANDOM = new SecureRandom();
    static String create() {
        byte[] bytes = new byte[24]; RANDOM.nextBytes(bytes);
        StringBuilder value = new StringBuilder(48);
        for (byte b : bytes) value.append(Character.forDigit((b & 255) >>> 4, 16)).append(Character.forDigit(b & 15, 16));
        return value.toString();
    }
    static boolean isValid(String value) { return value != null && value.matches("[0-9a-f]{48}"); }
    private OperationTokens() {}
}
