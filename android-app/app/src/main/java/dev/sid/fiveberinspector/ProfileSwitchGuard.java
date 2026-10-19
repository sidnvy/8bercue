package dev.sid.fiveberinspector;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Pure Java validation of 5ber profile state transitions (no Android/card IO). */
public final class ProfileSwitchGuard {
    public static final class Row {
        public final String id;
        public final boolean enabled;
        public Row(String id,boolean enabled){this.id=id;this.enabled=enabled;}
    }
    private ProfileSwitchGuard(){}

    public static String fingerprint(List<Row> rows) throws Exception {
        if(rows==null || rows.size()<2 || rows.size()>32)
            throw new SecurityException("Invalid inventory size");
        Set<String> unique=new HashSet<>();
        List<String> sorted=new ArrayList<>();
        int active=0;
        for(Row r:rows) {
            if(r==null || r.id==null || !r.id.matches("[0-9]{19,20}") || !unique.add(r.id))
                throw new SecurityException("Invalid or duplicate Profile identity");
            if(r.enabled)active++;
            sorted.add(r.id+":"+(r.enabled?"1":"0"));
        }
        if(active!=1)throw new SecurityException("Exactly one enabled Profile required");
        java.util.Collections.sort(sorted);
        MessageDigest hash=MessageDigest.getInstance("SHA-256");
        for(String line:sorted)hash.update((line+"\n").getBytes(StandardCharsets.US_ASCII));
        StringBuilder result=new StringBuilder();
        for(byte b:hash.digest())result.append(String.format(Locale.ROOT,"%02x",b&255));
        return result.toString();
    }

    public static boolean exactTransition(List<Row> before,List<Row> after,
            String oldId,String newId) throws Exception {
        fingerprint(before);fingerprint(after);
        if(oldId==null || newId==null || oldId.equals(newId) || before.size()!=after.size())
            return false;
        Map<String,Boolean> old=new HashMap<>(),now=new HashMap<>();
        for(Row row:before)old.put(row.id,row.enabled);
        for(Row row:after)now.put(row.id,row.enabled);
        if(!old.keySet().equals(now.keySet()) ||
            !Boolean.TRUE.equals(old.get(oldId)) ||
            !Boolean.FALSE.equals(old.get(newId))) return false;
        for(String id:old.keySet()) {
            boolean expected=id.equals(newId);
            if(now.get(id)!=expected) return false;
        }
        return true;
    }
    public static boolean restored(List<Row> initial,List<Row> current) throws Exception {
        return fingerprint(initial).equals(fingerprint(current));
    }
}
