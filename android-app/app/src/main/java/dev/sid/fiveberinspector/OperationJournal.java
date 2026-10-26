package dev.sid.fiveberinspector;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Properties;
import java.util.UUID;

/** Durable card/intent-bound records. Never stores credentials or raw card/profile IDs.
 * The transaction coordinator holds the shared card lock from begin through finish.
 * Reading a card NEVER authorizes overwriting an uncertain transaction.
 */
public final class OperationJournal {
    private final File file;
    private OperationJournal legacy;
    private String scopedCard;
    /** Old records stay byte-for-byte in place. Each card gets its own new current/history. */
    public static OperationJournal forCard(File root,String card) throws IOException {
        if(card==null||!card.matches("[a-f0-9]{64}"))throw new IOException("Complete card identity required");
        OperationJournal old=new OperationJournal(root);
        File cards=new File(root,"cards");
        if(Files.isSymbolicLink(cards.toPath())||!cards.getCanonicalFile().equals(cards.getAbsoluteFile()))
            throw new IOException("Unsafe card journal directory");
        Files.createDirectories(cards.toPath());
        Files.setPosixFilePermissions(cards.toPath(),PosixFilePermissions.fromString("rwx------"));
        OperationJournal result=new OperationJournal(new File(cards,card));
        try(FileChannel channel=FileChannel.open(root.toPath(),StandardOpenOption.READ)){channel.force(true);}
        try(FileChannel channel=FileChannel.open(cards.toPath(),StandardOpenOption.READ)){channel.force(true);}
        result.legacy=old;result.scopedCard=card;return result;
    }
    private boolean belongs(Properties p)throws IOException {
        if("idle".equals(p.getProperty("status")))return false;
        if(!"2".equals(p.getProperty("schema"))) {
            if(uncertain(p.getProperty("status")))throw new IOException("Unbound legacy uncertainty requires investigation");
            return false;
        }
        return scopedCard.equals(p.getProperty("card"));
    }
    private java.util.List<Properties> legacyHistory()throws IOException {
        java.util.List<Properties> result=new java.util.ArrayList<>();
        if(legacy==null)return result;
        legacy.checkRecoveryFiles();
        Path history=legacy.file.toPath().getParent().resolve("history");
        if(!Files.exists(history,LinkOption.NOFOLLOW_LINKS))return result;
        if(Files.isSymbolicLink(history)||!Files.isDirectory(history,LinkOption.NOFOLLOW_LINKS))throw new IOException("Unsafe legacy history");
        int count=0;
        try(DirectoryStream<Path> records=Files.newDirectoryStream(history)) {
            for(Path path:records) {
                if(++count>512||path.getFileName().toString().endsWith(".pending"))throw new IOException("Incomplete legacy history");
                Properties p=readRecord(path,false);
                if(!path.getFileName().toString().equals(p.getProperty("txId")+".properties"))throw new IOException("Legacy history identity mismatch");
                if(belongs(p))result.add(p);
            }
        }
        return result;
    }
    public synchronized Properties readTransaction(String tx)throws IOException {
        if(tx==null||!tx.matches("[a-f0-9-]{36}"))throw new IOException("Invalid transaction identity");
        Path path=file.toPath().getParent().resolve("history").resolve(tx+".properties");
        Properties p=null;
        if(Files.exists(path,LinkOption.NOFOLLOW_LINKS))p=readRecord(path,false);
        else for(Properties prior:legacyHistory())if(tx.equals(prior.getProperty("txId")))p=prior;
        if(p==null||!tx.equals(p.getProperty("txId"))||(scopedCard!=null&&!belongs(p)))throw new IOException("Missing card transaction");
        return p;
    }
    public static final class Binding {
        public final String operation, card, target, before, parameters;
        public Binding(String operation,String card,String target,String before,String parameters) {
            if(operation == null || !operation.matches("switch|lifecycle|download|enable|disable|rename|delete"))
                throw new SecurityException("Invalid operation");
            for(String value:new String[]{card,target,before,parameters})
                if(value == null || !value.matches("[a-fA-F0-9]{64}")) throw new SecurityException("Full identity digest required");
            this.operation=operation; this.card=card.toLowerCase(java.util.Locale.ROOT);
            this.target=target.toLowerCase(java.util.Locale.ROOT);this.before=before.toLowerCase(java.util.Locale.ROOT);
            this.parameters=parameters.toLowerCase(java.util.Locale.ROOT);
        }
    }
    public OperationJournal(File directory) throws IOException {
        if(!directory.getCanonicalFile().equals(directory.getAbsoluteFile()) || Files.isSymbolicLink(directory.toPath()))
            throw new IOException("Unsafe journal directory");
        Files.createDirectories(directory.toPath());
        Files.setPosixFilePermissions(directory.toPath(),PosixFilePermissions.fromString("rwx------"));
        file = new File(directory, "operation.properties");
    }
    public synchronized Properties read() throws IOException {
        Properties current=readRecord(file.toPath(), true);
        if(legacy==null)return current;
        if(!"idle".equals(current.getProperty("status"))) {
            if(!belongs(current))throw new IOException("Card journal binding mismatch");
            return current;
        }
        Properties prior=legacy.read();
        java.util.List<Properties> history=legacyHistory();
        if(belongs(prior))return prior; // The current pointer wins even after a wall-clock rollback.
        for(Properties record:history)if("idle".equals(current.getProperty("status"))
            ||new java.math.BigInteger(record.getProperty("startedAt")).compareTo(new java.math.BigInteger(current.getProperty("startedAt")))>0)current=record;
        return current;
    }
    private static Properties readRecord(Path path, boolean allowMissing) throws IOException {
        Properties p = new Properties();
        if(Files.isSymbolicLink(path)||!path.toFile().getCanonicalFile().equals(path.toFile().getAbsoluteFile()))throw new IOException("Unsafe journal record");
        if (!Files.exists(path,LinkOption.NOFOLLOW_LINKS)) { if(!allowMissing)throw new IOException("Missing history record"); p.setProperty("status", "idle"); return p; }
        if (!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.size(path)>4096)throw new IOException("Journal invalid");
        try (InputStream in=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)) { p.load(in); }
        if (!p.getProperty("status", "").matches("running|verified|not_completed|unknown|observed_unknown")
            || !p.getProperty("operation", "").matches("switch|lifecycle|download|enable|disable|rename|delete")
            || !p.getProperty("startedAt", "").matches("[0-9]{1,20}")) throw new IOException("Journal invalid");
        if(p.containsKey("schema")) {
            if(!"2".equals(p.getProperty("schema")) || !p.getProperty("txId","").matches("[a-f0-9-]{36}"))throw new IOException("Journal schema invalid");
            try {new Binding(p.getProperty("operation"),p.getProperty("card"),p.getProperty("target"),p.getProperty("before"),p.getProperty("parameters"));}
            catch(SecurityException invalid){throw new IOException("Journal binding invalid",invalid);}
        }
        validateDiagnostics(p);
        return p;
    }
    public synchronized boolean requiresReconciliation() throws IOException {
        checkRecoveryFiles();
        Properties current=read();
        boolean blocked = uncertain(current.getProperty("status")) && !reviewed(current);
        Path history = file.toPath().getParent().resolve("history");
        if (Files.exists(history, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(history) || !Files.isDirectory(history, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Unsafe journal history");
            int count=0;
            try (DirectoryStream<Path> records=Files.newDirectoryStream(history)) {
                for(Path record:records) {
                    if(++count>512)throw new IOException("Journal history capacity invalid");
                    if(record.getFileName().toString().endsWith(".pending")) {
                        // Interrupted durability commit. No automatic interpretation or replay.
                        blocked=true; continue;
                    }
                    Properties p=readRecord(record,false);
                    if(scopedCard!=null&&!belongs(p))throw new IOException("Card history binding mismatch");
                    if(!record.getFileName().toString().equals(p.getProperty("txId")+".properties"))
                        throw new IOException("History identity mismatch");
                    blocked |= uncertain(p.getProperty("status")) && !reviewed(p);
                }
            }
        }
        if(legacy!=null) {
            Properties prior=legacy.read();
            if(belongs(prior))blocked|=uncertain(prior.getProperty("status"))&&!reviewed(prior);
            for(Properties p:legacyHistory())blocked|=uncertain(p.getProperty("status"))&&!reviewed(p);
        }
        return blocked;
    }
    private static boolean uncertain(String status) {
        return "running".equals(status)||"unknown".equals(status)||"observed_unknown".equals(status);
    }
    /** Legacy reads carry no identity evidence and cannot resolve any lost write. */
    public synchronized String statusAfterReadOnlyReconciliation() throws IOException {
        Properties current=read();
        checkRecoveryFiles();
        return reviewed(current) ? ("download".equals(current.getProperty("operation")) ? "reviewed_abandoned" : "reviewed_observed") : current.getProperty("status", "unknown");
    }
    public synchronized String observe(String card,String snapshot) throws IOException {
        Properties p=read();
        if(!"2".equals(p.getProperty("schema")) || !p.getProperty("card","").equals(card)
                || snapshot==null || !snapshot.matches("[a-f0-9]{64}"))throw new IOException("Same bound card required");
        if(requiresReconciliation()) {
            p.setProperty("status","observed_unknown");p.setProperty("observedSnapshot",snapshot);save(p);
        }
        return p.getProperty("status");
    }
    /** Compatibility for synthetic/legacy callers only; unbound uncertainty stays blocked. */
    public synchronized void begin(String operation) throws IOException { beginInternal(operation,null); }
    public synchronized void begin(Binding binding) throws IOException { beginInternal(binding.operation,binding); }
    private void beginInternal(String operation,Binding binding) throws IOException {
        if (operation==null || !operation.matches("switch|lifecycle|download|enable|disable|rename|delete")) throw new IOException("Invalid operation");
        if(scopedCard!=null&&(binding==null||!scopedCard.equals(binding.card)))throw new IOException("Operation belongs to another card");
        if (requiresReconciliation()) throw new IOException("Unresolved transaction; observation alone cannot authorize writes");
        Properties p = new Properties();
        p.setProperty("operation",operation);p.setProperty("status","running");
        p.setProperty("startedAt", Long.toString(System.currentTimeMillis()));
        if(binding!=null) {
            p.setProperty("schema","2");p.setProperty("txId",UUID.randomUUID().toString());
            p.setProperty("card",binding.card);p.setProperty("target",binding.target);p.setProperty("before",binding.before);p.setProperty("parameters",binding.parameters);
        }
        save(p);
    }
    public synchronized void finish(String status) throws IOException {finish(status,new Properties());}
    public synchronized void finish(String status,Properties diagnostics) throws IOException {
        if (status==null || !status.matches("verified|not_completed|unknown|observed_unknown")) throw new IOException("Invalid outcome");
        Properties p = read();String before=p.getProperty("status");
        if(!"running".equals(before) && !("unknown".equals(before)&&"observed_unknown".equals(status)))
            throw new IOException("Illegal journal transition");
        if(diagnostics==null)throw new IOException("Missing diagnostic container");
        for(String key:diagnostics.stringPropertyNames())if(!key.startsWith("download"))throw new IOException("Invalid diagnostic key");
        if(!diagnostics.isEmpty()&&!"download".equals(p.getProperty("operation")))throw new IOException("Download diagnostics on another operation");
        validateDiagnostics(diagnostics);p.putAll(diagnostics);
        p.setProperty("status",status);save(p);
    }
    private static void validateDiagnostics(Properties fields)throws IOException {
        for(String key:fields.stringPropertyNames()) {
            if(!key.startsWith("download"))continue;
            String pattern;
            switch(key) {
                case "downloadPhase":pattern="LAUNCH|CARD_(CHALLENGE|INFO|INITIATE_HTTP|AUTHENTICATE_SERVER|AUTHENTICATE_CLIENT_HTTP|PREPARE_DOWNLOAD|BOUND_HTTP|LOAD_PACKAGE|CANCEL_CARD|CANCEL_HTTP|CANCELLED|ABORTED)|INITIATE_AUTHENTICATION|AUTHENTICATE_CLIENT|BOUND_PROFILE_PACKAGE|CANCEL_SESSION|NATIVE_EXIT";break;
                case "downloadFailure":pattern="NONE|TLS|DNS|NETWORK_TIMEOUT|INTERRUPTED|POLICY|IO|PROTOCOL|SERVER_REJECTED|NATIVE_REJECTED|NATIVE_STOP_UNCONFIRMED|WORKER_RESULT_UNAVAILABLE";break;
                case "downloadServerStatus":pattern="Executed-Success|Executed-WithWarning|Failed|Expired|not_reported";break;
                case "downloadSubjectCode":case "downloadReasonCode":pattern="not_reported|[0-9]{1,3}(\\.[0-9]{1,3}){1,3}";break;
                case "downloadHttpStatus":pattern="0|[1-5][0-9]{2}";break;
                case "downloadNativeCode":pattern="not_reported|-?[0-9]{1,4}";break;
                case "downloadWorkerStopped":case "downloadNativeStopped":pattern="true|false";break;
                default:throw new IOException("Invalid diagnostic key");
            }
            if(!fields.getProperty(key,"").matches(pattern))throw new IOException("Invalid diagnostic value");
        }
    }
    /** An explicit review disposition is separate from the original outcome; no write is replayed. */
    private Path resolutions() { return file.toPath().getParent().resolve("resolutions"); }
    private void checkRecoveryFiles() throws IOException {
        if(Files.exists(file.toPath().resolveSibling("operation.pending"),LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Interrupted operation commit");
        Path directory=resolutions();
        if(!Files.exists(directory,LinkOption.NOFOLLOW_LINKS))return;
        if(Files.isSymbolicLink(directory)||!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Unsafe recovery directory");
        int count=0;
        try(DirectoryStream<Path> entries=Files.newDirectoryStream(directory)) {
            for(Path entry:entries) {
                if(++count>256||Files.isSymbolicLink(entry)||!Files.isRegularFile(entry,LinkOption.NOFOLLOW_LINKS)
                    ||!entry.getFileName().toString().matches("[a-f0-9-]{36}\\.properties"))
                    throw new IOException("Incomplete or invalid recovery record");
            }
        }
    }
    public static String recordDigest(Properties record) throws IOException {
        try {
            java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
            for(String key:new java.util.TreeSet<>(record.stringPropertyNames())) {
                String value=record.getProperty(key);
                digest.update((key.length()+":"+key+value.length()+":"+value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
            return hex.toString();
        }catch(java.security.NoSuchAlgorithmException impossible){throw new IOException(impossible);}
    }
    private boolean reviewed(Properties original) throws IOException {
        String tx=original.getProperty("txId","");
        if(!tx.matches("[a-f0-9-]{36}"))return false;
        Path path=resolutions().resolve(tx+".properties");
        if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS)&&legacy!=null)path=legacy.resolutions().resolve(tx+".properties");
        if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return false;
        if(Files.isSymbolicLink(path)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>4096)
            throw new IOException("Invalid recovery record");
        Properties resolution=new Properties();try(InputStream in=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){resolution.load(in);}
        java.util.Set<String> keys=new java.util.HashSet<>(java.util.Arrays.asList("schema","txId","recordDigest","card","observedSnapshot","disposition","remoteOutcome","authorization","reviewedAt"));
        String operation=original.getProperty("operation","");
        boolean download="download".equals(operation)&&"1".equals(resolution.getProperty("schema"));
        boolean lifecycle=operation.matches("enable|disable|rename|switch")
            &&"2".equals(resolution.getProperty("schema"));
        if(!resolution.stringPropertyNames().equals(keys)||!(download||lifecycle)
            ||!"2".equals(original.getProperty("schema"))
            ||!(download ? original.getProperty("status","").matches("unknown|observed_unknown")
                : original.getProperty("status","").matches("running|unknown|observed_unknown"))
            ||!tx.equals(resolution.getProperty("txId"))||!recordDigest(original).equals(resolution.getProperty("recordDigest"))
            ||!original.getProperty("card","").equals(resolution.getProperty("card"))
            ||!(download ? original.getProperty("before","").equals(resolution.getProperty("observedSnapshot"))
                : resolution.getProperty("observedSnapshot","").matches("[a-f0-9]{64}"))
            ||!(download ? "abandoned_unknown_no_profile_delta".equals(resolution.getProperty("disposition"))
                : "accepted_observed_lifecycle_unknown".equals(resolution.getProperty("disposition")))
            ||!(download ? "unknown_do_not_reuse_code".equals(resolution.getProperty("remoteOutcome"))
                : "unknown_operation_not_replayed".equals(resolution.getProperty("remoteOutcome")))
            ||!resolution.getProperty("authorization","").matches("[a-f0-9]{64}")
            ||!resolution.getProperty("reviewedAt","").matches("[0-9]{1,20}"))
            throw new IOException("Recovery evidence does not match original transaction");
        return true;
    }
    /** Caller must hold exclusive card lock, confirm worker quiescence and explicitly authorize
     * abandonment after independent same-card readback. This never declares download success
     * or that a remote activation code is unused. Original current/history records stay intact. */
    public synchronized void abandonUnchangedDownload(String tx,String card,String snapshot,String expectedRecordDigest,String authorization) throws IOException {
        checkRecoveryFiles();Properties current=read();
        if(tx==null||!tx.equals(current.getProperty("txId"))||!"2".equals(current.getProperty("schema"))
            ||!"download".equals(current.getProperty("operation"))||!current.getProperty("status","").matches("unknown|observed_unknown")
            ||card==null||!card.matches("[a-f0-9]{64}")||!card.equals(current.getProperty("card"))
            ||snapshot==null||!snapshot.matches("[a-f0-9]{64}")||!snapshot.equals(current.getProperty("before"))
            ||!recordDigest(current).equals(expectedRecordDigest)||authorization==null||!authorization.matches("[a-f0-9]{64}")
            ||reviewed(current))throw new IOException("Review is stale, ineligible or not explicitly authorized");
        requireReviewableHistory(current);
        Files.createDirectories(resolutions());Files.setPosixFilePermissions(resolutions(),PosixFilePermissions.fromString("rwx------"));
        Properties resolution=new Properties();resolution.setProperty("schema","1");resolution.setProperty("txId",tx);
        resolution.setProperty("recordDigest",expectedRecordDigest);resolution.setProperty("card",card);resolution.setProperty("observedSnapshot",snapshot);
        resolution.setProperty("disposition","abandoned_unknown_no_profile_delta");resolution.setProperty("remoteOutcome","unknown_do_not_reuse_code");
        resolution.setProperty("authorization",authorization);resolution.setProperty("reviewedAt",Long.toString(System.currentTimeMillis()));
        persist(resolutions().resolve(tx+".properties").toFile(),resolution);
    }

    /** Read an authenticated, immutable lifecycle review when a crash left quarantine active. */
    public synchronized String reviewedLifecycleSnapshot() throws IOException {
        Properties current=read();
        if(!current.getProperty("operation","").matches("enable|disable|rename|switch")
            ||!reviewed(current))
            throw new IOException("No completed lifecycle review");
        Path path=resolutions().resolve(current.getProperty("txId")+".properties");
        if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS)&&legacy!=null)path=legacy.resolutions().resolve(current.getProperty("txId")+".properties");
        Properties resolution=new Properties();
        try(InputStream in=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){resolution.load(in);}
        return resolution.getProperty("observedSnapshot");
    }

    /** Read-only availability check. It grants no authority and never resolves uncertainty. */
    public synchronized boolean canReviewObservedLifecycle(String card) throws IOException {
        checkRecoveryFiles();
        Properties current=read();
        if(!"2".equals(current.getProperty("schema"))
            ||!current.getProperty("operation","").matches("enable|disable|rename|switch")
            ||!uncertain(current.getProperty("status"))
            ||card==null||!card.matches("[a-f0-9]{64}")||!card.equals(current.getProperty("card")))
            return false;
        try { requireReviewableHistory(current); return true; }
        catch(IOException unavailable) { return false; }
    }

    private void requireReviewableHistory(Properties current) throws IOException {
        String tx=current.getProperty("txId");
        java.util.List<Properties> records=new java.util.ArrayList<>(legacyHistory());
        Path history=file.toPath().getParent().resolve("history");
        if(Files.exists(history,LinkOption.NOFOLLOW_LINKS)) {
            if(Files.isSymbolicLink(history)||!Files.isDirectory(history,LinkOption.NOFOLLOW_LINKS))throw new IOException("Unsafe history");
            int count=0;
            try(DirectoryStream<Path> entries=Files.newDirectoryStream(history)) {
                for(Path entry:entries) {
                    if(++count>256||entry.getFileName().toString().endsWith(".pending"))throw new IOException("Incomplete journal history");
                    Properties prior=readRecord(entry,false);
                    if(!entry.getFileName().toString().equals(prior.getProperty("txId")+".properties"))throw new IOException("History identity mismatch");
                    if(scopedCard!=null&&!belongs(prior))throw new IOException("Card history binding mismatch");
                    records.add(prior);
                }
            }
        }
        boolean found=false;
        for(Properties prior:records) {
            if(tx.equals(prior.getProperty("txId"))) {
                if(!prior.equals(current))throw new IOException("Current/history disagreement");
                found=true;
            }else if(uncertain(prior.getProperty("status"))&&!reviewed(prior))throw new IOException("Another unresolved transaction on this card");
        }
        if(!found)throw new IOException("Original transaction history missing");
    }

    /**
     * User-authorized acceptance of a stable inventory after an unknown lifecycle result.
     * This DOES NOT verify or replay the old operation. The caller must hold the exclusive
     * card lock, confirm worker quiescence, make two same-card reads, and obtain user consent.
     * Download and delete are deliberately excluded: their effects need separate handling.
     */
    public synchronized void reviewObservedLifecycle(String tx,String card,String snapshot,
            String expectedRecordDigest,String authorization) throws IOException {
        checkRecoveryFiles();
        Properties current=read();
        if(tx==null||!tx.equals(current.getProperty("txId"))||!"2".equals(current.getProperty("schema"))
            ||!current.getProperty("operation","").matches("enable|disable|rename|switch")
            ||!current.getProperty("status","").matches("running|unknown|observed_unknown")
            ||card==null||!card.matches("[a-f0-9]{64}")||!card.equals(current.getProperty("card"))
            ||snapshot==null||!snapshot.matches("[a-f0-9]{64}")
            ||expectedRecordDigest==null||!expectedRecordDigest.equals(recordDigest(current))
            ||authorization==null||!authorization.matches("[a-f0-9]{64}")||reviewed(current))
            throw new IOException("Lifecycle review is stale or ineligible");
        requireReviewableHistory(current);
        Files.createDirectories(resolutions());
        Files.setPosixFilePermissions(resolutions(),PosixFilePermissions.fromString("rwx------"));
        Properties resolution=new Properties();
        resolution.setProperty("schema","2");
        resolution.setProperty("txId",tx);
        resolution.setProperty("recordDigest",expectedRecordDigest);
        resolution.setProperty("card",card);
        resolution.setProperty("observedSnapshot",snapshot);
        resolution.setProperty("disposition","accepted_observed_lifecycle_unknown");
        resolution.setProperty("remoteOutcome","unknown_operation_not_replayed");
        resolution.setProperty("authorization",authorization);
        resolution.setProperty("reviewedAt",Long.toString(System.currentTimeMillis()));
        persist(resolutions().resolve(tx+".properties").toFile(),resolution);
    }

    private static void persist(File output,Properties p) throws IOException {
        Path pending=new File(output.getParentFile(),output.getName().equals("operation.properties")?"operation.pending":output.getName()+".pending").toPath();
        if(Files.isSymbolicLink(pending)||Files.isSymbolicLink(output.toPath()))throw new IOException("Unsafe journal path");
        try(FileChannel channel=FileChannel.open(pending,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
            Files.setPosixFilePermissions(pending,PosixFilePermissions.fromString("rw-------"));
            OutputStream out=Channels.newOutputStream(channel);p.store(out,"8bercue operation metadata; no credentials or raw identifiers");out.flush();channel.force(true);
        }
        Files.move(pending,output.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        try(FileChannel dir=FileChannel.open(output.getParentFile().toPath(),StandardOpenOption.READ)){dir.force(true);}
    }
    private void save(Properties p) throws IOException {
        if(p.containsKey("txId")) {
            File history=new File(file.getParentFile(),"history");
            if(Files.isSymbolicLink(history.toPath()) || !history.getCanonicalFile().equals(history.getAbsoluteFile()))throw new IOException("Unsafe journal history");
            Files.createDirectories(history.toPath());Files.setPosixFilePermissions(history.toPath(),PosixFilePermissions.fromString("rwx------"));
            File[] records=history.listFiles((d,n)->n.endsWith(".properties"));
            File record=new File(history,p.getProperty("txId")+".properties");
            if(records==null || (records.length>=256&&!record.exists()))throw new IOException("Journal history capacity reached");
            persist(record,p);
        }
        persist(file,p);
    }
}
