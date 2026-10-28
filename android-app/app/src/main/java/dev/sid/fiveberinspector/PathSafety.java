package dev.sid.fiveberinspector;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Rejects symlinked state directories without comparing canonical and absolute paths,
 * which diverge on macOS for {@code /var} temp roots even when the directory is safe.
 *
 * @author Lingxiao Li （李凌霄）
 * @date 2026/10/28
 */
final class PathSafety {
    static void requireDirectDirectory(File directory) throws IOException {
        Path path = directory.toPath();
        if (Files.isSymbolicLink(path)) {
            throw new IOException("Unsafe directory");
        }
        if (!Files.exists(path)) {
            Path parent = path.getParent();
            if (parent != null) {
                requireDirectDirectory(parent.toFile());
            }
            return;
        }
        Path absolute = path.toAbsolutePath().normalize();
        if (!Files.isSameFile(absolute, absolute.toRealPath())) {
            throw new IOException("Unsafe directory");
        }
    }

    static void requireDirectDirectorySecurity(File directory) {
        try {
            requireDirectDirectory(directory);
        } catch (IOException failure) {
            throw new SecurityException("Unsafe directory", failure);
        }
    }

    private PathSafety() {}
}
