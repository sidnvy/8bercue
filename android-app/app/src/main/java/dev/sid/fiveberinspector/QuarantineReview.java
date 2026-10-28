package dev.sid.fiveberinspector;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/**
 * Preserve an old worker quarantine as immutable evidence after an explicitly
 * reviewed, stable, same-card recovery. Only call under the original kernel lock,
 * after independently proving all native/old management workers have stopped.
 * A crash before the journal resolution is persisted still blocks all writes.
 */
final class QuarantineReview {
    static boolean archive(Path lockPath,String card,String journalDigest,String snapshot,
            String authorization,String apkDigest) throws IOException {
        if(!CardTransactionLock.ownedByCurrentThread()||CardTransactionLock.isQuarantined())
            throw new SecurityException("Exclusive non-quarantined lock required");
        for(String value:new String[]{card,journalDigest,snapshot,authorization,apkDigest})
            if(value==null||!value.matches("[a-f0-9]{64}"))
                throw new SecurityException("Complete review binding required");
        Properties evidence=new Properties();
        evidence.setProperty("schema","1");
        evidence.setProperty("disposition","user_reviewed_worker_quarantine");
        evidence.setProperty("cardDigest",card);
        evidence.setProperty("journalDigest",journalDigest);
        evidence.setProperty("observedSnapshot",snapshot);
        evidence.setProperty("authorization",authorization);
        evidence.setProperty("apkDigest",apkDigest);
        evidence.setProperty("reviewedAt",Long.toString(System.currentTimeMillis()));
        return preserve(lockPath,evidence,"reviewed","reviewed-quarantines");
    }
    /** Worker liveness and a card's unknown outcome are separate facts. The caller
     * proves quiescence twice under this same kernel lock before archiving the marker.
     * No operation is reviewed, resolved, retried, or granted permission here. */
    static boolean archiveStoppedWorkers(Path lockPath,String markerDigest,String apkDigest)throws IOException {
        if(!CardTransactionLock.ownedByCurrentThread()||CardTransactionLock.isQuarantined())throw new SecurityException("Exclusive non-quarantined lock required");
        for(String value:new String[]{markerDigest,apkDigest})if(value==null||!value.matches("[a-f0-9]{64}"))throw new SecurityException("Complete worker evidence required");
        Path marker=lockPath.resolveSibling(lockPath.getFileName()+".quarantine");
        CardTransactionLock.validateMarker(marker);
        try {
            byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(marker));
            StringBuilder hex=new StringBuilder();for(byte v:digest)hex.append(String.format(java.util.Locale.ROOT,"%02x",v&255));
            if(!markerDigest.equals(hex.toString()))throw new IOException("Quarantine changed during worker recovery");
        }catch(java.security.NoSuchAlgorithmException impossible){throw new IOException(impossible);}
        Properties evidence=new Properties();evidence.setProperty("schema","2");
        evidence.setProperty("disposition","workers_stopped_card_outcomes_unchanged");
        evidence.setProperty("markerDigest",markerDigest);evidence.setProperty("apkDigest",apkDigest);
        evidence.setProperty("observedAt",Long.toString(System.currentTimeMillis()));
        return preserve(lockPath,evidence,"stopped","stopped-worker-quarantines");
    }
    private static boolean preserve(Path lockPath,Properties evidence,String kind,String receiptDirectory)throws IOException {
        Path root=lockPath.toAbsolutePath().getParent();
        PathSafety.requireDirectDirectory(root.toFile());
        Path marker=lockPath.resolveSibling(lockPath.getFileName()+".quarantine");
        if(!Files.exists(marker,LinkOption.NOFOLLOW_LINKS))return false;
        CardTransactionLock.validateMarker(marker);
        String id=UUID.randomUUID().toString();
        Path archive=root.resolve(lockPath.getFileName()+".quarantine."+kind+"."+id);
        Path directory=root.resolve(receiptDirectory);
        if(Files.isSymbolicLink(directory))throw new IOException("Unsafe review record directory");
        Files.createDirectories(directory);
        Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("rwx------"));
        // Persist the new directory entry in its parent BEFORE any quarantine
        // rename. fsync(directory) alone cannot guarantee its name survives power loss.
        try(FileChannel parent=FileChannel.open(root,StandardOpenOption.READ)){parent.force(true);}
        evidence.setProperty("markerName",archive.getFileName().toString());
        Path receipt=directory.resolve(id+".properties");
        try(FileChannel file=FileChannel.open(receipt,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
            Files.setPosixFilePermissions(receipt,PosixFilePermissions.fromString("rw-------"));
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            evidence.store(bytes,"Worker quarantine evidence; card operation outcomes unchanged");
            ByteBuffer data=ByteBuffer.wrap(bytes.toByteArray());
            while(data.hasRemaining())file.write(data);
            file.force(true);
        }
        try(FileChannel dir=FileChannel.open(directory,StandardOpenOption.READ)){dir.force(true);}
        // Atomic relocation preserves the exact marker bytes. Never unlink the lock inode.
        Files.move(marker,archive,StandardCopyOption.ATOMIC_MOVE);
        try(FileChannel dir=FileChannel.open(root,StandardOpenOption.READ)){dir.force(true);}
        return true;
    }
    private QuarantineReview(){}
}