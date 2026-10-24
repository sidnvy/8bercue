package dev.sid.fiveberinspector;

public final class OnDeviceAdbCodeBufferOfflineTest {
    public static void main(String[] args) {
        OnDeviceAdbCodeBuffer input = new OnDeviceAdbCodeBuffer();
        assert input.size() == 0;
        assert input.take() == null;
        assert !input.append(-1) && !input.append(10);
        for (int digit = 0; digit < 6; digit++) assert input.append(digit);
        assert input.complete();
        assert !input.append(8);
        assert input.masked().length() == 6;
        input.delete();
        assert !input.complete();
        assert input.append(9);
        char[] result = input.take();
        assert result.length == 6;
        assert input.size() == 0;
        java.util.Arrays.fill(result, '\0');
        input.append(1);
        input.clear();
        assert input.size() == 0;
        System.out.println("PASS keyboard-free pairing input state tests");
    }
}
