package dev.sid.fiveberinspector;

import java.util.*;

/** Prove a lost state-change result from its complete, precommitted intent.
 * No card I/O and no mutation. Readability alone is never sufficient evidence.
 */
final class LifecycleObservedOutcome {
    static boolean proves(Properties intent,List<ProfileLifecycleGuard.Row> after) throws Exception {
        if(!"2".equals(intent.getProperty("schema"))
                ||!intent.getProperty("status","").matches("running|unknown|observed_unknown")
                ||!intent.getProperty("operation","").matches("enable|switch|disable")
                ||!IntentDigests.of("").equals(intent.getProperty("parameters")))return false;
        ProfileLifecycleGuard.fingerprint(after);
        String target=null;
        for(ProfileLifecycleGuard.Row row:after)
            if(IntentDigests.of(row.id).equals(intent.getProperty("target")))target=row.id;
        if(target==null)return false;
        boolean enable=!"disable".equals(intent.getProperty("operation"));
        if(ProfileLifecycleGuard.selected(after,target).enabled!=enable)return false;
        ProfileLifecycleGuard.Operation op=enable?ProfileLifecycleGuard.Operation.ENABLE:ProfileLifecycleGuard.Operation.DISABLE;
        // Reconstruct every valid prior active-profile choice, without inventing
        // any prior metadata. Its full fingerprint MUST equal the committed one.
        for(int active=-1;active<after.size();active++) {
            List<ProfileLifecycleGuard.Row> before=new ArrayList<>();
            for(int i=0;i<after.size();i++) {
                ProfileLifecycleGuard.Row row=after.get(i);
                before.add(new ProfileLifecycleGuard.Row(row.id,i==active,row.nickname,row.provider,row.name));
            }
            if(!intent.getProperty("before","").equals(ProfileLifecycleGuard.fingerprint(before)))continue;
            try {if(ProfileLifecycleGuard.exactTransition(op,before,after,target,""))return true;}
            catch(SecurityException invalidPriorState){return false;}
        }
        return false;
    }
    private LifecycleObservedOutcome(){}
}
