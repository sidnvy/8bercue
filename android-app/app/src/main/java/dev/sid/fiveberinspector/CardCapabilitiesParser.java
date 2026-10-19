package dev.sid.fiveberinspector;

import java.util.*;
import java.security.MessageDigest;

/** Bounded ES10c capability decoding. Only a small non-identifying field set is returned. */
public final class CardCapabilitiesParser {
 private CardCapabilitiesParser(){}
 private static final class Node {final int tag,start,end;Node(int t,int s,int e){tag=t;start=s;end=e;}}
 private static Node node(byte[] b,int offset,int bound){
  if(offset>=bound)throw new SecurityException("Missing capability tag");
  int tag=b[offset++]&255;
  if((tag&31)==31){boolean ended=false;for(int i=0;i<3;i++){if(offset>=bound)throw new SecurityException("Truncated capability tag");int v=b[offset++]&255;tag=tag<<8|v;if((v&128)==0){ended=true;break;}}if(!ended)throw new SecurityException("Capability tag too long");}
  if(offset>=bound)throw new SecurityException("Missing capability length");int length=b[offset++]&255;
  if((length&128)!=0){int count=length&127;if(count<1||count>2||offset+count>bound)throw new SecurityException("Invalid capability length");length=0;for(int i=0;i<count;i++)length=length<<8|(b[offset++]&255);}
  if(length>bound-offset)throw new SecurityException("Capability field exceeds response");return new Node(tag,offset,offset+length);
 }
 private static long quantity(byte[] b,Node n){
  int length=n.end-n.start;if(length<1||length>8||length==8&&(b[n.start]&128)!=0)throw new SecurityException("Unsupported capability quantity");
  long result=0;for(int i=n.start;i<n.end;i++)result=result<<8|(b[i]&255);return result;
 }
 private static String version(byte[] b,Node n){if(n.end-n.start!=3)throw new SecurityException("Malformed capability version");return (b[n.start]&255)+"."+(b[n.start+1]&255)+"."+(b[n.start+2]&255);}
 private static boolean additionalProfile(byte[] b,Node n){
  int length=n.end-n.start;if(length<1||length>33)throw new SecurityException("Malformed RSP capability");int unused=b[n.start]&255;
  if(unused>7||length==1&&unused!=0||length>1&&((b[n.end-1]&255)&((1<<unused)-1))!=0)throw new SecurityException("Malformed RSP capability padding");
  return length>1&&(b[n.start+1]&128)!=0;
 }
 public static Map<String,Object> parse(byte[] b){
  if(b==null||b.length<3||b.length>16384)throw new SecurityException("Invalid capability response size");
  Node outer=node(b,0,b.length);if(outer.tag!=0xBF22||outer.end!=b.length)throw new SecurityException("Unexpected capability response");
  Map<String,Object> result=new LinkedHashMap<>();Set<Integer> fields=new HashSet<>();
  for(int at=outer.start;at<outer.end;){Node n=node(b,at,outer.end);at=n.end;if(!fields.add(n.tag))throw new SecurityException("Duplicate capability field");
   switch(n.tag){
    case 0x81:result.put("profileVersion",version(b,n));break;
    case 0x82:result.put("gsmaVersion",version(b,n));break;
    case 0x83:result.put("firmwareVersion",version(b,n));break;
    case 0x86:result.put("ts102241Version",version(b,n));break;
    case 0x87:result.put("globalPlatformVersion",version(b,n));break;
    case 0x88:result.put("additionalProfileSupported",additionalProfile(b,n));break;
    case 0x84:
     Set<Integer> resources=new HashSet<>();
     for(int offset=n.start;offset<n.end;){Node r=node(b,offset,n.end);offset=r.end;if(!resources.add(r.tag))throw new SecurityException("Duplicate resource field");
      if(r.tag==0x81)result.put("installedApplicationCount",quantity(b,r));
      if(r.tag==0x82)result.put("freeNonvolatileMemoryBytes",quantity(b,r));
      if(r.tag==0x83)result.put("freeVolatileMemoryBytes",quantity(b,r));
     }break;
    default: // Certificates, accreditation, identifiers and raw capability bytes are not exported.
   }
  }
  if(result.isEmpty())throw new SecurityException("No supported capability fields");return Collections.unmodifiableMap(result);
 }
 /** Checks the actual EID digest against enrollment; returns only 4+4 decimal digits. */
 public static String maskedEid(byte[] b,String expectedHash)throws Exception{
  if(b==null||b.length!=21)throw new SecurityException("Invalid EID response length");
  Node outer=node(b,0,b.length);Node value=node(b,outer.start,outer.end);
  if(outer.tag!=0xBF3E||outer.end!=b.length||value.tag!=0x5A||value.end!=outer.end||value.end-value.start!=16)throw new SecurityException("Invalid EID response");
  byte[] eid=Arrays.copyOfRange(b,value.start,value.end);byte[] digest=MessageDigest.getInstance("SHA-256").digest(eid);StringBuilder hash=new StringBuilder();for(int i=0;i<32;i++)hash.append(String.format(Locale.ROOT,"%02X",digest[i]&255));
  if(expectedHash==null||!(expectedHash.length()==64?hash.toString().equalsIgnoreCase(expectedHash):expectedHash.length()==16&&hash.substring(0,16).equalsIgnoreCase(expectedHash)))throw new SecurityException("Enrolled card identity mismatch");
  StringBuilder decimal=new StringBuilder();for(byte v:eid){int high=(v>>>4)&15,low=v&15;if(high>9||low>9)throw new SecurityException("Invalid EID digits");decimal.append(high).append(low);}
  return decimal.substring(0,4)+"…"+decimal.substring(28);
 }
}
