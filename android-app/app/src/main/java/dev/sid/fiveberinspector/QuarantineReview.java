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
        Path root=lockPath.toAbsolutePath().getParent();
        if(!root.toFile().getCanonicalFile().equals(root.toFile()))
            throw new IOException("Unsafe review directory");
        Path marker=lockPath.resolveSibling(lockPath.getFileName()+".quarantine");
        if(!Files.exists(marker,LinkOption.NOFOLLOW_LINKS))return false;
        CardTransactionLock.validateMarker(marker);
        String id=UUID.randomUUID().toString();
        Path archive=root.resolve(lockPath.getFileName()+".quarantine.reviewed."+id);
        Path directory=root.resolve("reviewed-quarantines");
        if(Files.isSymbolicLink(directory))throw new IOException("Unsafe review record directory");
        Files.createDirectories(directory);
        Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("rwx------"));
        // Persist the new directory entry in its parent BEFORE any quarantine
        // rename. fsync(directory) alone cannot guarantee its name survives power loss.
        try(FileChannel parent=FileChannel.open(root,StandardOpenOption.READ)){parent.force(true);}
        Properties evidence=new Properties();
        evidence.setProperty("schema","1");
        evidence.setProperty("disposition","user_reviewed_worker_quarantine");
        evidence.setProperty("cardDigest",card);
        evidence.setProperty("journalDigest",journalDigest);
        evidence.setProperty("observedSnapshot",snapshot);
        evidence.setProperty("authorization",authorization);
        evidence.setProperty("apkDigest",apkDigest);
        evidence.setProperty("reviewedAt",Long.toString(System.currentTimeMillis()));
        evidence.setProperty("markerName",archive.getFileName().toString());
        Path receipt=directory.resolve(id+".properties");
        try(FileChannel file=FileChannel.open(receipt,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
            Files.setPosixFilePermissions(receipt,PosixFilePermissions.fromString("rw-------"));
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            evidence.store(bytes,"Explicit quarantine review; original unknown outcome retained");
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