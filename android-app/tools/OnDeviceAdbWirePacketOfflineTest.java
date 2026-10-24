package dev.sid.fiveberinspector;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public final class OnDeviceAdbWirePacketOfflineTest {
    static int checked;
    static void ok(boolean condition, String why) {
        if (!condition) throw new AssertionError(why);
        checked++;
    }
    static byte[] packet(int cmd, int arg0, int arg1, byte[] payload) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OnDeviceAdbWirePacket.write(out, cmd, arg0, arg1, payload);
        return out.toByteArray();
    }
    static void invalid(byte[] wire) throws Exception {
        try (OnDeviceAdbWirePacket ignored = OnDeviceAdbWirePacket.read(new ByteArrayInputStream(wire))) {
            throw new AssertionError("malformed packet accepted");
        } catch (IOException expected) { checked++; }
    }
    public static void main(String[] args) throws Exception {
        byte[] payload = new byte[]{0,1,2,(byte)255};
        byte[] wire = packet(OnDeviceAdbWirePacket.A_STLS,0x01000000,0,payload);
        ok(wire.length==24+payload.length,"ADB packet length");
        try (OnDeviceAdbWirePacket parsed = OnDeviceAdbWirePacket.read(new ByteArrayInputStream(wire))) {
            ok(parsed.command==OnDeviceAdbWirePacket.A_STLS,"STLS opcode");
            ok(parsed.arg0==0x01000000 && parsed.arg1==0,"STLS version");
            ok(Arrays.equals(parsed.payload,payload),"round trip payload");
            parsed.close();ok(Arrays.equals(parsed.payload,new byte[4]),"zero payload");
        }
        ok(OnDeviceAdbWirePacket.sum(payload)==258,"byte unsigned additive checksum");
        byte[] tampered = wire.clone();tampered[tampered.length-1]=3;invalid(tampered);
        byte[] magic = wire.clone();magic[20]^=1;invalid(magic);
        byte[] negativeLength = wire.clone();ByteBuffer.wrap(negativeLength).order(ByteOrder.LITTLE_ENDIAN).putInt(12,-1);invalid(negativeLength);
        byte[] oversized = wire.clone();ByteBuffer.wrap(oversized).order(ByteOrder.LITTLE_ENDIAN).putInt(12,OnDeviceAdbWirePacket.MAX_PAYLOAD+1);invalid(oversized);
        byte[] unknown = wire.clone();ByteBuffer.wrap(unknown).order(ByteOrder.LITTLE_ENDIAN).putInt(0,0x11223344);invalid(unknown);
        invalid(Arrays.copyOf(wire,wire.length-2));
        invalid(Arrays.copyOf(wire,23));
        byte[] noProgress = new byte[24];invalid(noProgress);
        for (int cmd : new int[]{OnDeviceAdbWirePacket.A_CNXN,OnDeviceAdbWirePacket.A_AUTH,
                OnDeviceAdbWirePacket.A_OPEN,OnDeviceAdbWirePacket.A_OKAY,
                OnDeviceAdbWirePacket.A_CLSE,OnDeviceAdbWirePacket.A_WRTE}) {
            try (OnDeviceAdbWirePacket p = OnDeviceAdbWirePacket.read(
                    new ByteArrayInputStream(packet(cmd,0,0,new byte[0])))) {
                ok(p.command==cmd,"known opcode");
            }
        }
        try { packet(123,0,0,new byte[1]); throw new AssertionError("allowed unknown opcode"); }
        catch(IOException expected){checked++;}
        System.out.println("PASS "+checked+" bounded ADB wire packet parser assertions");
    }
}
