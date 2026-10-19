package dev.sid.fiveberinspector;

import java.util.Locale;

/** Accumulates only bounded ES10c.GetEID responses, including SW61 fragments. */
public final class EidReadFrames {
    private static final int MAX_FRAMES = 6;
    private static final int EXPECTED_BER_HEX_LEN = 42;
    private final int channel;
    private final StringBuilder ber = new StringBuilder(EXPECTED_BER_HEX_LEN);
    private int frames;
    private boolean finished;

    public EidReadFrames(int channel) {
        if (channel < 1 || channel > 3) throw new SecurityException("Bad logical channel");
        this.channel = channel;
    }

    /** Returns the exact read-only GET RESPONSE APDU, or null after SW9000. */
    public String accept(String reply) {
        if (finished || frames >= MAX_FRAMES || reply == null || reply.length() < 4
                || (reply.length() & 1) != 0 || reply.length() > 2 * (EXPECTED_BER_HEX_LEN / 2 + 2)
                || !reply.matches("[a-fA-F0-9]+"))
            throw new SecurityException("Invalid GetEID fragment");
        String frame = reply.toUpperCase(Locale.ROOT);
        ber.append(frame, 0, frame.length() - 4);
        if (ber.length() > EXPECTED_BER_HEX_LEN)
            throw new SecurityException("Oversized GetEID BER");
        frames++;
        int sw1 = Integer.parseInt(frame.substring(frame.length()-4, frame.length()-2),16);
        int sw2 = Integer.parseInt(frame.substring(frame.length()-2),16);
        if (sw1 == 0x61 && frames < MAX_FRAMES && ber.length() < EXPECTED_BER_HEX_LEN)
            return String.format(Locale.ROOT,"%02XC00000%02X",0x80 | channel,sw2);
        if (sw1 != 0x90 || sw2 != 0 || ber.length() != EXPECTED_BER_HEX_LEN
                || !ber.toString().startsWith("BF3E125A10"))
            throw new SecurityException("Unexpected GetEID final response");
        finished = true;
        return null;
    }

    public byte[] eid() {
        if (!finished) throw new SecurityException("Incomplete GetEID");
        byte[] result = new byte[16];
        for (int i=0; i<result.length; i++)
            result[i] = (byte)Integer.parseInt(ber.substring(10+i*2,12+i*2),16);
        return result;
    }
}
