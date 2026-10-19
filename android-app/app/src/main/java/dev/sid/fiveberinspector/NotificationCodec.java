package dev.sid.fiveberinspector;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Strict bounded ES10b notification encoding, independent of Android and native CLI output. */
final class NotificationCodec {
  static final int MAX_BYTES = 65536;
  static byte[] command(long sequence, boolean remove) {
    if (sequence < 0 || sequence > 0xffffffffL) throw new SecurityException("Notification sequence invalid");
    byte[] value = BigInteger.valueOf(sequence).toByteArray();
    byte[] number = tlv(0x80, value);
    return tlv(remove ? 0xBF30 : 0xBF2B, remove ? number : tlv(0xA0, number));
  }
  static byte[] tlv(int tag, byte[] value) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    if (tag > 255) out.write(tag >>> 8);out.write(tag);
    if (value.length < 128) out.write(value.length);
    else if (value.length <= 255) { out.write(0x81);out.write(value.length); }
    else { out.write(0x82);out.write(value.length >>> 8);out.write(value.length); }
    out.write(value, 0, value.length);return out.toByteArray();
  }
  static final class Node {
    final byte[] bytes;final int tag, begin, start, end;
    Node(byte[] b,int tag,int begin,int start,int end) {bytes=b;this.tag=tag;this.begin=begin;this.start=start;this.end=end;}
    byte[] value() { return Arrays.copyOfRange(bytes,start,end); }
    byte[] encoded() { return Arrays.copyOfRange(bytes,begin,end); }
    List<Node> children() {
      List<Node> nodes=new ArrayList<>();int p=start;
      while(p<end) { if(nodes.size()>=64)throw new SecurityException("Notification field budget");Node n=read(bytes,p,end);nodes.add(n);p=n.end; }
      return nodes;
    }
    Node one(int wanted) {
      Node found=null;
      for(Node n:children())if(n.tag==wanted) {if(found!=null)throw new SecurityException("Duplicate notification field");found=n;}
      if(found==null)throw new SecurityException("Missing notification field");return found;
    }
  }
  static Node read(byte[] b,int begin,int limit) {
    int p=begin;if(p>=limit)throw new SecurityException("Missing BER tag");int tag=b[p++]&255;
    if((tag&31)==31) {if(p>=limit)throw new SecurityException("Truncated BER tag");int next=b[p++]&255;if((next&128)!=0 || (next&127)<31)throw new SecurityException("Unsupported BER tag");tag=(tag<<8)|next;}
    if(p>=limit)throw new SecurityException("Missing BER length");int len=b[p++]&255;
    if(len>=128) {int size=len&127;if(size<1||size>2||size>limit-p)throw new SecurityException("Invalid BER length");len=0;for(int i=0;i<size;i++)len=(len<<8)|(b[p++]&255);if(len<128 || (size==2&&len<256))throw new SecurityException("Noncanonical BER length");}
    if(len>limit-p)throw new SecurityException("Truncated BER value");return new Node(b,tag,begin,p,p+len);
  }
  static Node root(byte[] b,int tag) {
    if(b==null||b.length<2||b.length>MAX_BYTES)throw new SecurityException("Notification bounds");
    Node n=read(b,0,b.length);if(n.tag!=tag||n.end!=b.length)throw new SecurityException("Unexpected notification envelope");return n;
  }
  static long number(Node n) {
    byte[] b=n.value();if(b.length<1||b.length>5||(b[0]&128)!=0||(b.length>1&&b[0]==0&&(b[1]&128)==0))throw new SecurityException("Invalid notification integer");
    long value=new BigInteger(b).longValue();if(value<0||value>0xffffffffL)throw new SecurityException("Notification integer out of range");return value;
  }
  static final class Pending {
    final String host;final byte[] signedPayload;
    Pending(String host,byte[] signedPayload){this.host=host;this.signedPayload=signedPayload;}
  }
  static Pending pending(byte[] response,long sequence,String expectedIccid,String approvedHost) {
    Node list=root(response,0xBF2B).one(0xA0);
    List<Node> entries=list.children();
    if(entries.size()!=1||entries.get(0).tag!=0xBF37)throw new SecurityException("Expected one installation notification");
    Node signed=entries.get(0), metadata=signed.one(0xBF27).one(0xBF2F);
    if(number(metadata.one(0x80))!=sequence)throw new SecurityException("Notification sequence changed");
    byte[] digits=metadata.one(0x5A).value();StringBuilder id=new StringBuilder();
    for(int i=0;i<digits.length;i++) {int lo=digits[i]&15,hi=(digits[i]>>>4)&15;
      if(lo>9)throw new SecurityException("Invalid ICCID");id.append(lo);
      if(hi<=9)id.append(hi);else if(hi!=15||i!=digits.length-1)throw new SecurityException("Invalid ICCID");}
    if(expectedIccid==null||!expectedIccid.matches("[0-9]{18,22}")||!expectedIccid.equals(id.toString()))throw new SecurityException("Notification profile changed");
    byte[] address=metadata.one(0x0C).value();
    if(address.length<3||address.length>253)throw new SecurityException("Notification recipient invalid");
    for(byte c:address)if(c<0x21||c>0x7e)throw new SecurityException("Notification recipient invalid");
    String host=ActivationCode.validateHost(new String(address,StandardCharsets.US_ASCII));
    if(!host.equals(ActivationCode.validateHost(approvedHost)))throw new RecipientMismatch();
    return new Pending(host,signed.encoded());
  }
  static boolean absent(byte[] response) {
    Node envelope=root(response,0xBF2B);List<Node> fields=envelope.children();
    return fields.size()==1&&fields.get(0).tag==0x81&&number(fields.get(0))==1;
  }
  static boolean removed(byte[] response) {return number(root(response,0xBF30).one(0x80))==0;}
  static final class RecipientMismatch extends SecurityException {RecipientMismatch(){super("Notification recipient differs from approved operator");}}
  private NotificationCodec(){}
}
