package dev.sid.fiveberinspector;

import java.nio.file.*;
import java.util.*;

/** Actual journal/proof code, synthetic cards; never a device compatibility claim. */
public final class LifecycleObservedOutcomeOfflineTest {
    static int checks;
    static final String CARD="a".repeat(64),OTHER="b".repeat(64);
    static final String ONE="1234567890123456789",TWO="2234567890123456789";
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static ProfileLifecycleGuard.Row row(String id,boolean on){return new ProfileLifecycleGuard.Row(id,on,"","Provider","Profile");}
    static List<ProfileLifecycleGuard.Row> rows(boolean one,boolean two){return List.of(row(ONE,one),row(TWO,two));}
    static Properties intent(String operation,List<ProfileLifecycleGuard.Row> before)throws Exception{
        Properties p=new Properties();p.setProperty("schema","2");p.setProperty("status","unknown");p.setProperty("operation",operation);
        p.setProperty("target",IntentDigests.of(ONE));p.setProperty("before",ProfileLifecycleGuard.fingerprint(before));p.setProperty("parameters",IntentDigests.of(""));return p;
    }
    static void persist(Path p,Properties fields)throws Exception{try(var out=Files.newOutputStream(p)){fields.store(out,"Synthetic fixture");}}
    public static void main(String[] args)throws Exception{
        for(String operation:List.of("enable","switch","disable")){
            List<ProfileLifecycleGuard.Row> before=rows(operation.equals("disable"),!operation.equals("disable"));
            List<ProfileLifecycleGuard.Row> after=rows(!operation.equals("disable"),false);
            Properties p=intent(operation,before);
            check(LifecycleObservedOutcome.proves(p,after),"Exact full transition can be proved: "+operation);
            check(!LifecycleObservedOutcome.proves(p,before),"Readable unchanged state is not success");
            check(LifecycleObservedOutcome.proves(p,List.of(after.get(1),after.get(0))),"Inventory order is irrelevant to the transition");
            check(!LifecycleObservedOutcome.proves(p,List.of(after.get(0))),"Missing unrelated profile fails");
            var changed=new ProfileLifecycleGuard.Row(TWO,false,"changed","Provider","Profile");
            check(!LifecycleObservedOutcome.proves(p,List.of(after.get(0),changed)),"Unrelated metadata change fails");
            for(String key:List.of("target","before","parameters")){
                Properties different=new Properties();different.putAll(p);different.setProperty(key,OTHER);
                check(!LifecycleObservedOutcome.proves(different,after),"Wrong committed "+key+" fails");
            }
        }
        Properties enable=intent("enable",rows(false,false));
        check(LifecycleObservedOutcome.proves(enable,rows(true,false)),"Enable from no active profile");
        for(String operation:List.of("rename","download","delete","lifecycle")){
            Properties unsupported=new Properties();unsupported.putAll(enable);unsupported.setProperty("operation",operation);
            check(!LifecycleObservedOutcome.proves(unsupported,rows(true,false)),"No inference for "+operation);
        }
        for(String status:List.of("verified","not_completed","idle")){
            Properties done=new Properties();done.putAll(enable);done.setProperty("status",status);
            check(!LifecycleObservedOutcome.proves(done,rows(true,false)),"Completed records are not reinterpreted");
        }
        Path root=Files.createTempDirectory("exact-lifecycle-");
        for(String scenario:List.of("exact","wrong-card","unchanged","metadata","other-unknown","pending","missing-history","mismatched-history")){
            Path dir=Files.createDirectory(root.resolve(scenario));OperationJournal journal=new OperationJournal(dir.toFile());
            List<ProfileLifecycleGuard.Row> before=rows(false,true),after=rows(true,false);
            journal.begin(new OperationJournal.Binding("enable",CARD,IntentDigests.of(ONE),ProfileLifecycleGuard.fingerprint(before),IntentDigests.of("")));journal.finish("unknown");
            Properties original=journal.read();String tx=original.getProperty("txId");Path history=dir.resolve("history/"+tx+".properties");
            byte[] current=Files.readAllBytes(dir.resolve("operation.properties")),past=Files.readAllBytes(history);
            if(scenario.equals("pending"))Files.writeString(dir.resolve("history/other.pending"),"interrupted");
            if(scenario.equals("missing-history"))Files.delete(history);
            if(scenario.equals("mismatched-history")){Properties changed=journal.read();changed.setProperty("startedAt","1");persist(history,changed);}
            if(scenario.equals("other-unknown")){Properties other=journal.read();String id=UUID.randomUUID().toString();other.setProperty("txId",id);persist(dir.resolve("history/"+id+".properties"),other);}
            if(scenario.equals("unchanged"))after=before;
            if(scenario.equals("metadata"))after=List.of(row(ONE,true),new ProfileLifecycleGuard.Row(TWO,false,"changed","Provider","Profile"));
            boolean result=false;try{result=journal.verifyObservedLifecycle(scenario.equals("wrong-card")?OTHER:CARD,after);}catch(java.io.IOException failClosed){}
            check(result==scenario.equals("exact"),"Only exact eligible transaction receives a receipt: "+scenario);
            check(Arrays.equals(current,Files.readAllBytes(dir.resolve("operation.properties"))),"Original unknown current bytes retained");
            if(!scenario.matches("missing-history|mismatched-history"))check(Arrays.equals(past,Files.readAllBytes(history)),"Original unknown history bytes retained");
            check(journal.requiresReconciliation()!=scenario.equals("exact"),"Only proven outcome unblocks: "+scenario);
            if(result){
                check("verified_observed".equals(journal.statusAfterReadOnlyReconciliation()),"Observation status differs from old native response");
                OperationJournal reopened=new OperationJournal(dir.toFile());check(!reopened.requiresReconciliation(),"Receipt survives restart");
                check(!reopened.verifyObservedLifecycle(CARD,after),"Receipt is not overwritten");
                Path receipt=dir.resolve("resolutions/"+tx+".properties");Properties bad=new Properties();try(var in=Files.newInputStream(receipt)){bad.load(in);}bad.setProperty("recordDigest",OTHER);persist(receipt,bad);
                boolean rejected=false;try{reopened.requiresReconciliation();}catch(java.io.IOException expected){rejected=true;}check(rejected,"Mismatched proof fails closed");
            }
        }
        System.out.println("PASS "+checks+" exact lifecycle observation and immutable journal assertions (synthetic card I/O)");
    }
}
