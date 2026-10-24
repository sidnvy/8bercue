package dev.sid.fiveberinspector;

import java.util.Arrays;

/** In-memory only, six-digit secret. Never render digits in notification text. */
final class OnDeviceAdbCodeBuffer {
    private final char[] digits = new char[6];
    private int length;

    boolean append(int digit) {
        if (digit < 0 || digit > 9 || length == digits.length) return false;
        digits[length++] = (char) ('0' + digit);
        return true;
    }

    void delete() {
        if (length != 0) digits[--length] = '\0';
    }

    int size() { return length; }

    boolean complete() { return length == digits.length; }

    /** Caller must explicitly zero the returned copy when pairing ends. */
    char[] take() {
        if (!complete()) return null;
        char[] copy = digits.clone();
        clear();
        return copy;
    }

    void clear() {
        Arrays.fill(digits, '\0');
        length = 0;
    }

    /** Nonsecret progress only. */
    String masked() {
        StringBuilder b = new StringBuilder(6);
        for (int i = 0; i < 6; i++) b.append(i < length ? '●' : '○');
        return b.toString();
    }
}
