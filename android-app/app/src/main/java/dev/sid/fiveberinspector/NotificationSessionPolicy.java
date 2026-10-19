package dev.sid.fiveberinspector;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
/** Exact sequence only: one retrieval, one acknowledged removal, bounded response continuations. */
final class NotificationSessionPolicy {
  private final long sequence;
  private final ByteArrayOutputStream response=new ByteArrayOutputStream();
  private int phase,frames,remaining;
  private boolean outstanding;
  NotificationSessionPolicy(long sequence){NotificationCodec.command(sequence,false);this.sequence=sequence;}
  byte[] next(int channel) {
    if(channel<1||channel>3||outstanding||++frames>260)throw new SecurityException("Notification APDU state invalid");
    byte[] apdu;
    if(remaining!=0) apdu=new byte[]{(byte)(0x80|channel),(byte)0xc0,0,0,(byte)(remaining==256?0:remaining)};
    else {
      if(phase!=0&&phase!=2)throw new SecurityException("No notification command permitted");
      byte[] body=NotificationCodec.command(sequence,phase==2);apdu=new byte[body.length+5];
      apdu[0]=(byte)(0x80|channel);apdu[1]=(byte)0xe2;apdu[2]=(byte)0x91;apdu[4]=(byte)body.length;System.arraycopy(body,0,apdu,5,body.length);
    }
    outstanding=true;return apdu;
  }
  byte[] accept(byte[] bytes) {
    if(!outstanding||bytes==null||bytes.length<2||bytes.length>8192)throw new SecurityException("Invalid notification response");
    outstanding=false;int len=bytes.length-2,sw1=bytes[len]&255,sw2=bytes[len+1]&255;
    if(response.size()+len>NotificationCodec.MAX_BYTES)throw new SecurityException("Notification response budget");
    response.write(bytes,0,len);
    if(sw1==0x61){remaining=sw2==0?256:sw2;return null;}
    remaining=0;if(sw1!=0x90||sw2!=0)throw new SecurityException("Notification command rejected");
    byte[] result=response.toByteArray();response.reset();phase=phase==0?1:3;return result;
  }
  void acknowledged() {if(phase!=1||outstanding||remaining!=0)throw new SecurityException("Notification not retrieved");phase=2;}
  boolean complete(){return phase==3;}
}
