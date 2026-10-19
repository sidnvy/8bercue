package dev.sid.fiveberinspector;

import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Exact inventory checks: installation must add one disabled profile and change nothing else. */
public final class DownloadGuard {
    public static final class Row {
        final String id,metadata;
        final boolean enabled;
        public Row(String id,boolean enabled,String metadata) {
            this.id=id;this.enabled=enabled;this.metadata=metadata==null?"":metadata;
        }
    }
    public static String fingerprint(List<Row> rows) throws Exception {
        if(rows==null || rows.size()>32)throw new SecurityException("Invalid Profile inventory");
        Set<String> ids=new HashSet<>();List<String> encoded=new ArrayList<>();int enabled=0;
        for(Row row:rows) {
            if(row==null || row.id==null || !row.id.matches("[0-9]{19,20}") || !ids.add(row.id))
                throw new SecurityException("Invalid or duplicate Profile");
            if(row.enabled)enabled++;
            encoded.add(row.id+":"+row.enabled+":"+row.metadata.length()+":"+row.metadata);
        }
        if(enabled>1)throw new SecurityException("Conflicting enabled Profiles");
        Collections.sort(encoded);
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        for(String item:encoded)digest.update((item.length()+":"+item).getBytes(StandardCharsets.UTF_8));
        StringBuilder result=new StringBuilder();
        for(byte b:digest.digest())result.append(String.format(Locale.ROOT,"%02x",b&255));
        return result.toString();
    }
    public static boolean exactInstalled(List<Row> before,List<Row> after,String installedId) throws Exception {
        fingerprint(before);fingerprint(after);
        if(installedId==null || after.size()!=before.size()+1)return false;
        Map<String,Row> old=new HashMap<>();for(Row r:before)old.put(r.id,r);
        int added=0;
        for(Row row:after) {
            Row previous=old.get(row.id);
            if(previous==null) {
                if(row.enabled || !row.id.equals(installedId))return false;
                added++;
            } else if(previous.enabled!=row.enabled || !previous.metadata.equals(row.metadata))return false;
        }
        return added==1;
    }
    private DownloadGuard(){}
}
