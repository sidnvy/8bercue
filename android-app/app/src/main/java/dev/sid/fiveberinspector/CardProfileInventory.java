package dev.sid.fiveberinspector;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Internal-only, read-only card-side ICCID lookup for fixed two-profile
 * switching. Raw ICCIDs are never persisted, logged or sent over the app
 * socket; they exist only in this Shell process until one-shot lpac invocation.
 */
public final class CardProfileInventory {
    public static final class Entry {
        public final String provider;
        public final String name;
        public final String iccid;
        public final boolean enabled;
        public final String nickname;
        Entry(String provider, String name, String iccid, boolean enabled) {
            this(provider,name,iccid,enabled,"");
        }
        Entry(String provider,String name,String iccid,boolean enabled,String nickname) {
            this.provider=provider;this.name=name;this.iccid=iccid;this.enabled=enabled;this.nickname=nickname;
        }
    }
    private static final String AID="A0000005591010FFFFFFFF8900000100";
    private CardProfileInventory() {}

    static JSONObject req(String func, String param) throws Exception {
        JSONObject payload=new JSONObject().put("func",func);
        payload.put("param",param==null?JSONObject.NULL:param);
        return new JSONObject().put("type","apdu").put("payload",payload);
    }
    static byte[] fromHex(String value){
        if(value==null || (value.length()&1)!=0)throw new SecurityException("Invalid card reply hex");
        byte[] b=new byte[value.length()/2];
        for(int i=0;i<b.length;i++)
            b[i]=(byte)Integer.parseInt(value.substring(i*2,i*2+2),16);
        return b;
    }
    private static String toHex(byte[] bytes){
        StringBuilder out=new StringBuilder();
        for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02X",b&255));
        return out.toString();
    }

    private static final class Node {
        int tag,start,end;
        Node(int tag,int start,int end){this.tag=tag;this.start=start;this.end=end;}
    }
    private static Node readNode(byte[] bytes,int offset,int limit){
        if(offset>=limit)throw new SecurityException("BER tag missing");
        int tag=bytes[offset++]&255;
        if((tag&0x1F)==0x1F){
            boolean done=false;
            for(int i=0;i<3;i++){
                if(offset>=limit)throw new SecurityException("Truncated BER tag");
                int next=bytes[offset++]&255;
                tag=(tag<<8)|next;
                if((next&128)==0){done=true;break;}
            }
            if(!done)throw new SecurityException("BER tag length");
        }
        if(offset>=limit)throw new SecurityException("Truncated BER length");
        int len=bytes[offset++]&255;
        if((len&128)!=0){
            int k=len&127;
            if(k<1||k>2)throw new SecurityException("Unsupported BER length");
            len=0;
            for(int i=0;i<k;i++){
                if(offset>=limit)throw new SecurityException("Truncated BER length");
                len=(len<<8)|(bytes[offset++]&255);
            }
        }
        if(len>limit-offset)throw new SecurityException("BER node bounds");
        return new Node(tag,offset,offset+len);
    }

    private static String bcdIccid(byte[] bytes,Node node) {
        StringBuilder sb=new StringBuilder();
        for(int i=node.start;i<node.end;i++){
            int lo=bytes[i]&15,hi=(bytes[i]>>>4)&15;
            if(lo<10)sb.append(lo);
            else if(lo!=15)throw new SecurityException("Invalid ICCID BCD");
            if(hi<10)sb.append(hi);
            else if(hi!=15)throw new SecurityException("Invalid ICCID BCD");
        }
        String id=sb.toString();
        if(!id.matches("[0-9]{18,22}"))
            throw new SecurityException("Unexpected ICCID length");
        return id;
    }
    private static String plain(byte[] bytes,Node node) {
        if(node.end-node.start>90)throw new SecurityException("Long card name");
        return new String(bytes,node.start,node.end-node.start,StandardCharsets.UTF_8);
    }
    private static List<Entry> decode(byte[] data) {
        Node outer=readNode(data,0,data.length);
        if(outer.tag!=0xBF2D || outer.end!=data.length)
            throw new SecurityException("Wrong GetProfilesInfo response");
        Node ok=readNode(data,outer.start,outer.end);
        if(ok.tag!=0xA0 || ok.end!=outer.end)
            throw new SecurityException("Missing successful profile list");
        List<Entry> entries=new ArrayList<>();
        for(int i=ok.start;i<ok.end;){
            Node entry=readNode(data,i,ok.end);i=entry.end;
            if(entry.tag!=0xE3)continue;
            String id="",name="",provider="",nickname="";
            java.util.Set<Integer> fields=new java.util.HashSet<>();
            boolean enabled=false;
            for(int j=entry.start;j<entry.end;){
                Node f=readNode(data,j,entry.end);j=f.end;
                if(!fields.add(f.tag))throw new SecurityException("Duplicate profile field");
                if(f.tag==0x5A)id=bcdIccid(data,f);
                if(f.tag==0x91)provider=plain(data,f);
                if(f.tag==0x92)name=plain(data,f);
                if(f.tag==0x90)nickname=plain(data,f);
                if(f.tag==0x9F70) {
                    if(f.end-f.start!=1 || (data[f.start]&255)>1)throw new SecurityException("Unexpected profile state");
                    enabled=(data[f.start]&255)==1;
                }
            }
            if(id.isEmpty() || !fields.contains(0x9F70))throw new SecurityException("Missing ICCID");
            entries.add(new Entry(provider,name,id,enabled,nickname));
            if(entries.size()>16)throw new SecurityException("Unexpected card Profile count");
        }
        return entries;
    }

    public static List<Entry> list() throws Exception {
        return listInternal(new NativeLpacReadOnlyBridge());
    }
    static List<Entry> listKnownCardReadOnly(FiveberReaderSelector.Target expected) throws Exception {
        return listInternal(new NativeLpacReadOnlyBridge(expected));
    }
    private static List<Entry> listInternal(NativeLpacReadOnlyBridge bridge) throws Exception {
        try {
            if(bridge.handle(req("connect",null)).getJSONObject("payload").getInt("ecode")!=0)
                throw new SecurityException("Card not connected");
            int ch=bridge.handle(req("logic_channel_open",AID))
                .getJSONObject("payload").getInt("ecode");
            if(ch<1 || ch>3)throw new SecurityException("Invalid channel");
            ByteArrayOutputStream total=new ByteArrayOutputStream();
            String command=String.format(Locale.ROOT,"%02XE2910003BF2D00",0x80|ch);
            for(int f=0;f<24;f++){
                JSONObject p=bridge.handle(req("transmit",command)).getJSONObject("payload");
                if(p.getInt("ecode")!=0)throw new SecurityException("Card profile command rejected");
                byte[] reply=fromHex(p.getString("data"));
                if(reply.length<2 || total.size()+reply.length>32768)
                    throw new SecurityException("Invalid card response length");
                total.write(reply,0,reply.length-2);
                int sw1=reply[reply.length-2]&255,sw2=reply[reply.length-1]&255;
                if(sw1==0x90 && sw2==0x00)
                    return decode(total.toByteArray());
                if(sw1!=0x61)throw new SecurityException("Unsuccessful card profile response");
                command=String.format(Locale.ROOT,"%02XC00000%02X",0x80|ch,sw2);
            }
            throw new SecurityException("Too many card response frames");
        }finally{
            bridge.close();
        }
    }
}
