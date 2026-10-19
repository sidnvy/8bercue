package dev.sid.fiveberinspector;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Pure, exact full-inventory policy; identifiers never leave the service. */
public final class ProfileLifecycleGuard {
 public enum Operation { ENABLE, DISABLE, RENAME, DELETE }
 public static final class Row {
  public final String id,nickname,provider,name; public final boolean enabled;
  public Row(String id,boolean enabled,String nickname,String provider,String name){this.id=id;this.enabled=enabled;this.nickname=nickname;this.provider=provider;this.name=name;}
 }
 private ProfileLifecycleGuard(){}
 public static String nickname(String value){
  if(value==null || value.getBytes(StandardCharsets.UTF_8).length>64 || value.codePoints().anyMatch(c->Character.isISOControl(c)||c==0xFFFD||c>=0xD800&&c<=0xDFFF))throw new SecurityException("Nickname must be at most 64 UTF-8 bytes without control characters");
  return value;
 }
 public static String fingerprint(List<Row> rows)throws Exception{
  if(rows==null||rows.size()>32)throw new SecurityException("Invalid inventory size");
  Set<String> ids=new HashSet<>();List<String> canonical=new ArrayList<>();int enabled=0;
  for(Row r:rows){
   if(r==null||r.id==null||!r.id.matches("[0-9]{19,20}")||!ids.add(r.id)||r.nickname==null||r.provider==null||r.name==null)throw new SecurityException("Invalid or duplicate profile");
   if(r.enabled)enabled++;
   canonical.add(r.id+":"+r.enabled+":"+part(r.nickname)+part(r.provider)+part(r.name));
  }
  if(enabled>1)throw new SecurityException("Multiple enabled profiles are unsupported");
  Collections.sort(canonical);MessageDigest h=MessageDigest.getInstance("SHA-256");
  for(String s:canonical)h.update(s.getBytes(StandardCharsets.UTF_8));
  StringBuilder b=new StringBuilder();for(byte v:h.digest())b.append(String.format(Locale.ROOT,"%02x",v&255));return b.toString();
 }
 /** Selection snapshots bind order as well as full contents; mutation postflight remains order-independent. */
 public static String selectionFingerprint(List<Row> rows)throws Exception{
  MessageDigest h=MessageDigest.getInstance("SHA-256");h.update(fingerprint(rows).getBytes(StandardCharsets.US_ASCII));
  for(Row r:rows)h.update((r.id+"\n").getBytes(StandardCharsets.US_ASCII));
  StringBuilder b=new StringBuilder();for(byte v:h.digest())b.append(String.format(Locale.ROOT,"%02x",v&255));return b.toString();
 }
 private static String part(String s){return s.length()+":"+s;}
 public static Row selected(List<Row> rows,String id){for(Row r:rows)if(r.id.equals(id))return r;throw new SecurityException("Profile no longer present");}
 public static void validate(Operation op,List<Row> before,String id,String name)throws Exception{
  fingerprint(before);Row target=selected(before,id);
  if(op==Operation.RENAME)nickname(name);
  if(op==Operation.ENABLE&&target.enabled)throw new SecurityException("Profile already enabled");
  if(op==Operation.DELETE&&target.enabled)throw new SecurityException("Only an inactive profile may be deleted");
  if(op==Operation.DISABLE&&!target.enabled)throw new SecurityException("Profile is already disabled");
 }
 public static boolean exactTransition(Operation op,List<Row> before,List<Row> after,String id,String name)throws Exception{
  validate(op,before,id,name);fingerprint(after);List<Row> expected=new ArrayList<>();
  for(Row r:before){boolean target=r.id.equals(id);if(target&&op==Operation.DELETE)continue;
   expected.add(new Row(r.id,op==Operation.ENABLE?target:op==Operation.DISABLE&&target?false:r.enabled,target&&op==Operation.RENAME?name:r.nickname,r.provider,r.name));}
  return fingerprint(expected).equals(fingerprint(after));
 }
 /** Byte-identical libeuicc 2.3.0 ES10c command, short DER lengths only. */
 public static byte[] command(Operation op,String id,String name,int channel){
  if(channel<1||channel>3||id==null||!id.matches("[0-9]{19,20}"))throw new SecurityException("Invalid command target");
  byte[] bcd=new byte[10];for(int i=0;i<10;i++){int lo=id.charAt(i*2)-'0';int hi=i*2+1<id.length()?id.charAt(i*2+1)-'0':15;bcd[i]=(byte)(lo|hi<<4);}
  java.io.ByteArrayOutputStream body=new java.io.ByteArrayOutputStream();
  boolean state=op==Operation.ENABLE||op==Operation.DISABLE;
  byte[] nick=op==Operation.RENAME?nickname(name).getBytes(StandardCharsets.UTF_8):new byte[0];
  body.write(0xBF);body.write(op==Operation.ENABLE?0x31:op==Operation.DISABLE?0x32:op==Operation.DELETE?0x33:0x29);
  body.write(state?17:12+(op==Operation.RENAME?2+nick.length:0));
  if(state){body.write(0xA0);body.write(12);}body.write(0x5A);body.write(10);body.write(bcd,0,10);
  if(state){body.write(0x81);body.write(1);body.write(0xFF);}if(op==Operation.RENAME){body.write(0x90);body.write(nick.length);body.write(nick,0,nick.length);}
  byte[] data=body.toByteArray(),result=new byte[data.length+5];result[0]=(byte)(0x80|channel);result[1]=(byte)0xE2;result[2]=(byte)0x91;result[4]=(byte)data.length;System.arraycopy(data,0,result,5,data.length);return result;
 }
}
