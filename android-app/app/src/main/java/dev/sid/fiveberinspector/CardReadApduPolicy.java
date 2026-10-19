package dev.sid.fiveberinspector;
import java.util.Arrays;
/** One exact read request per logical channel, bounded conditional GET RESPONSE only. */
public final class CardReadApduPolicy {
 private final int channel;private final boolean capabilities;
 private boolean querySent,continuation,awaitingResponse;private int expectedLe=-1,frames,bytes;
 public CardReadApduPolicy(int channel,boolean capabilities){if(channel<1||channel>3)throw new SecurityException("Invalid logical channel");this.channel=channel;this.capabilities=capabilities;}
 public void authorize(byte[] command){
  if(command==null||frames>=24||awaitingResponse)throw new SecurityException("Read request budget exhausted");
  boolean eid=Arrays.equals(command,new byte[]{(byte)(0x80|channel),(byte)0xE2,(byte)0x91,0,6,(byte)0xBF,0x3E,3,0x5C,1,0x5A});
  boolean profiles=Arrays.equals(command,new byte[]{(byte)(0x80|channel),(byte)0xE2,(byte)0x91,0,3,(byte)0xBF,0x2D,0});
  boolean info=capabilities&&Arrays.equals(command,new byte[]{(byte)(0x80|channel),(byte)0xE2,(byte)0x91,0,3,(byte)0xBF,0x22,0});
  boolean next=querySent&&continuation&&Arrays.equals(command,new byte[]{(byte)(0x80|channel),(byte)0xC0,0,0,(byte)expectedLe});
  if((!querySent&&(eid||profiles||info))||next){querySent=true;continuation=false;awaitingResponse=true;frames++;return;}
  throw new SecurityException("Command outside read-only allowlist");
 }
 public void acceptResponse(byte[] reply){
  if(!awaitingResponse||reply==null||reply.length<2||reply.length>8192||bytes+reply.length-2>32768)throw new SecurityException("Malformed read response");
  awaitingResponse=false;bytes+=reply.length-2;continuation=(reply[reply.length-2]&255)==0x61;expectedLe=continuation?reply[reply.length-1]&255:-1;
 }
}
