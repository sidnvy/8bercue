#!/usr/bin/env python3
"""Execute the exact production confirmRecoveryReview body with instrumented Android
dependencies. This is a contract test, not proof of physical device behavior."""
from pathlib import Path
import os
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
production = (root / "app/src/main/java/dev/sid/fiveberinspector/ProfileUserService.java").read_text()
start = "@Override public synchronized String confirmRecoveryReview(String opaqueToken) {"
end = "    @Override public synchronized String getOperationStatus()"
assert production.count(start) == 1 and production.count(end) == 1
method = production.split(start, 1)[1].split(end, 1)[0]
assert 'ReadOnlyRecovery.requireReviewQuiescent();' in method
assert method.index('ReadOnlyRecovery.requireReviewQuiescent();') < method.index('record.reviewObservedLifecycle(')
assert method.index('record.reviewObservedLifecycle(') < method.index('ReadOnlyRecovery.archiveAfterReview(')
assert 'ReadOnlyRecovery.acquire()' in method
source = r'''package dev.sid.fiveberinspector;
import java.util.*;

public final class RecoveryReviewEntryTest {
    private static int checks;
    private static void check(boolean ok, String why) {
        checks++;
        if (!ok) throw new AssertionError(why);
    }

    private RecoveryReviewPlan pendingRecoveryReview;
    private static RecoveryReviewPlan actual;
    private static final OperationJournal journal = new OperationJournal();
    private static int observations, invalidations;
    private static boolean authorized = true;
    private static List<RecoveryProcessGuard.Worker> workers = Collections.emptyList();
    private static List<RecoveryProcessGuard.Worker> secondSampleWorkers = Collections.emptyList();

    static class ServiceCaller {
        static void requireApp() {
            if (!authorized) throw new SecurityException("Caller not authorized");
        }
        static ShellCall asShell() {
            requireApp();
            return new ShellCall();
        }
        static class ShellCall implements AutoCloseable {
            public void close() {}
        }
    }
    static class CardTransactionLock {
        static boolean locked, quarantine;
        static boolean persistentQuarantine() { return quarantine; }
        static void release() {
            if (!locked) throw new AssertionError("Lock was not held");
            locked = false;
        }
    }
    static class ReadOnlyRecovery {
        static int quiescentChecks, archiveCalls;
        static boolean quiescentProven;
        static void acquire() {
            if (CardTransactionLock.locked) throw new AssertionError("Double lock");
            CardTransactionLock.locked = true;
        }
        static void requireReviewQuiescent() {
            if (!CardTransactionLock.locked) throw new SecurityException("Missing exclusive lock");
            quiescentChecks++;
            // Execute the actual production worker predicate, not a hand-written
            // approximation. The backend process is PID 99 in this hermetic setup.
            RecoveryProcessGuard.quiescent(workers, 99);
            quiescentChecks++;
            RecoveryProcessGuard.quiescent(secondSampleWorkers, 99);
            quiescentProven = true;
        }
        static void archiveAfterReview(String card,String digest,String snapshot,String authorization) {
            if (!quiescentProven || !CardTransactionLock.locked || journal.reviews != 1)
                throw new SecurityException("Unchecked archive");
            archiveCalls++;
        }
    }
    static class IntentDigests {
        static String of(String token) {
            return "c".repeat(64);
        }
    }
    static class OperationJournal {
        int reviews;
        void reviewObservedLifecycle(String tx,String card,String snapshot,String digest,String authorization) {
            if (!CardTransactionLock.locked || !ReadOnlyRecovery.quiescentProven)
                throw new SecurityException("Review not protected by worker/lock");
            reviews++;
        }
    }
    static class JSONObject {
        private static final Map<String,Map<String,Object>> wire = new HashMap<>();
        private final Map<String,Object> values = new HashMap<>();
        JSONObject() {}
        JSONObject(String encoded) { values.putAll(wire.get(encoded)); }
        JSONObject put(String name,Object value) { values.put(name,value); return this; }
        boolean optBoolean(String name, boolean fallback) {
            Object v = values.get(name);
            return v instanceof Boolean ? (Boolean) v : fallback;
        }
        String optString(String name) {return String.valueOf(values.getOrDefault(name,""));}
        public String toString() {
            String id = "r" + wire.size();
            wire.put(id,new HashMap<>(values));
            return id;
        }
    }
    static class RecoveryReviewPlan {
        final String tx,card,reader,snapshot,recordDigest,operation,token;
        final boolean alreadyReviewed;
        RecoveryReviewPlan(String token,String card) {
            this(token,card,"tx");
        }
        RecoveryReviewPlan(String token,String card,String transaction) {
            this.token=token;this.card=card;
            tx=transaction;reader="reader";snapshot="snap";recordDigest="digest";
            operation="enable";alreadyReviewed=false;
        }
        void requireFresh(String candidate) {
            if (!token.equals(candidate)) throw new SecurityException("Expired");
        }
    }

    OperationJournal journal() {
        ServiceCaller.requireApp();
        return journal;
    }
    RecoveryReviewPlan observeLifecycleForReview(OperationJournal ignored) {
        if (!CardTransactionLock.locked) throw new SecurityException("Missing exclusive lock");
        observations++;
        return actual;
    }
    void invalidatePreviews() { invalidations++; }
    String safeOperationError(String step,Throwable cause) {
        return new JSONObject().put("ok",false).put("error",cause.getClass().getSimpleName()).toString();
    }
    public synchronized String confirmRecoveryReview(String opaqueToken) {
''' + method + r'''
    private static void reset(RecoveryReviewEntryTest service) {
        service.pendingRecoveryReview=new RecoveryReviewPlan("token","original-card");
        actual=new RecoveryReviewPlan("observed-token","original-card");
        workers=Collections.emptyList();
        secondSampleWorkers=Collections.emptyList();
        authorized=true;
        CardTransactionLock.locked=false;
        CardTransactionLock.quarantine=false;
        ReadOnlyRecovery.quiescentChecks=0;
        ReadOnlyRecovery.archiveCalls=0;
        ReadOnlyRecovery.quiescentProven=false;
        journal.reviews=0;
        observations=0;
        invalidations=0;
    }
    private static void failed(RecoveryReviewEntryTest service,String message) {
        JSONObject response=new JSONObject(service.confirmRecoveryReview("token"));
        check(!response.optBoolean("ok",false), message+": rejected");
        check(journal.reviews==0, message+": no disposition");
        check(ReadOnlyRecovery.archiveCalls==0, message+": no archive");
        check(!CardTransactionLock.locked,message+": released exclusive lock");
        check(service.pendingRecoveryReview==null,message+": proposal consumed");
    }
    public static void main(String[] args) {
        RecoveryReviewEntryTest service=new RecoveryReviewEntryTest();
        reset(service);
        workers=List.of(new RecoveryProcessGuard.Worker(321,2000,1,"lpac",
            "/data/local/tmp/fiveber-lpac-lab/lpac","",""));
        failed(service,"native worker still active");
        check(ReadOnlyRecovery.quiescentChecks==1,"first worker sample rejected before a second read");

        reset(service);
        secondSampleWorkers=List.of(new RecoveryProcessGuard.Worker(321,2000,1,"lpac",
            "/data/local/tmp/fiveber-lpac-lab/lpac","",""));
        failed(service,"native worker appears during second sample");
        check(ReadOnlyRecovery.quiescentChecks==2,"second worker sample rejected");

        reset(service);
        workers=List.of(new RecoveryProcessGuard.Worker(321,2000,1,
            RecoveryProcessGuard.NAME,"/system/bin/app_process64","","1234"));
        failed(service,"old peer management process still active");

        reset(service);
        actual=new RecoveryReviewPlan("observed-token","wrong-card");
        failed(service,"changed full eUICC identity");
        check(ReadOnlyRecovery.quiescentChecks==0,"mismatched card rejected before worker gate");

        reset(service);
        actual=new RecoveryReviewPlan("observed-token","original-card","different-transaction");
        failed(service,"different transaction identity");
        check(ReadOnlyRecovery.quiescentChecks==0,"mismatched transaction rejected before worker gate");

        reset(service);
        authorized=false;
        failed(service,"unauthenticated Binder caller");
        check(observations==0,"unauthorized caller cannot inventory");

        reset(service);
        JSONObject result=new JSONObject(service.confirmRecoveryReview("token"));
        check(result.optBoolean("ok",false),"stable same-card confirmation passes");
        check("reviewed_observed".equals(result.optString("status")),"review status matches");
        check(journal.reviews==1,"one immutable disposition created");
        check(ReadOnlyRecovery.quiescentChecks==2,"two worker snapshots verified before review");
        check(!CardTransactionLock.locked,"lock released after successful review");
        check(invalidations==1,"pending previews invalidated");
        check(service.pendingRecoveryReview==null,"token consumed");

        reset(service);
        CardTransactionLock.quarantine=true;
        result=new JSONObject(service.confirmRecoveryReview("token"));
        check(result.optBoolean("ok",false),"quarantined recovery can pass only after confirmation");
        check(ReadOnlyRecovery.archiveCalls==1,"quarantine archive happens after quiescence");
        System.out.println("PASS " + checks +
            " actual production confirmRecoveryReview entry assertions (stubbed device I/O)");
    }
}
'''
with tempfile.TemporaryDirectory(prefix="8bercue-review-entry-") as temp:
    test_dir = Path(temp)
    java = test_dir / "dev/sid/fiveberinspector/RecoveryReviewEntryTest.java"
    java.parent.mkdir(parents=True, exist_ok=True)
    java.write_text(source)
    home = os.environ.get("JAVA_HOME")
    tool = lambda n: str(Path(home) / "bin" / n) if home else n
    path = root / "app/src/main/java/dev/sid/fiveberinspector"
    subprocess.run([tool("javac"), "-d", temp, str(path / "ConnectionCommand.java"),
                    str(path / "RecoveryProcessGuard.java"), str(java)], check=True)
    subprocess.run([tool("java"), "-cp", temp,
                    "dev.sid.fiveberinspector.RecoveryReviewEntryTest"], check=True)