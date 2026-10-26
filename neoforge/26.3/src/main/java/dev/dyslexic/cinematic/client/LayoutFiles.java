package dev.dyslexic.cinematic.client;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Replaces a complete profile file without exposing partially written JSON. */
final class LayoutFiles {
    private LayoutFiles() {}

    static void write(Path target, String json) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temp, json);
        for (int attempt = 0; ; attempt++) {
            try {
                try {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (AccessDeniedException e) {
                // Windows sync/indexing software can briefly hold the destination after a save.
                if (attempt == 3) throw e;
                try {
                    Thread.sleep(20L << attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while replacing layout file", interrupted);
                }
            }
        }
    }
}
