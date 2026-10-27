package com.hushsign.agent.runtime.sensitive;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

/**
 * Keeps the full log directory readable only by the account that runs the
 * agent (P7-S5).
 *
 * <p>The clean log is safe to hand over, so its permissions are nobody's
 * problem. The full log is the one that carries identities, and a log file left
 * world-readable on a shared box is the same leak as printing it into the
 * console. The systemd unit also sets this through {@code LogsDirectoryMode},
 * but the agent must not depend on having been started that way.
 */
public final class LogDirectory {

    private static final Set<PosixFilePermission> OWNER_ONLY =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE);

    private LogDirectory() {
    }

    /**
     * Creates the directory if needed and narrows it to the owner. Returns
     * whether the permissions now hold; on a file system without POSIX
     * permissions (Windows, a Windows share) there is nothing to narrow and the
     * answer is false — the caller logs that rather than pretending.
     */
    public static boolean harden(Path directory) {
        if (directory == null) {
            return false;
        }
        try {
            Files.createDirectories(directory);
            if (!Files.getFileStore(directory).supportsFileAttributeView("posix")) {
                return false;
            }
            Files.setPosixFilePermissions(directory, OWNER_ONLY);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** The directory the agent logs into, honouring {@code HS_LOG_DIR}. */
    public static Path configured() {
        String configured = System.getenv("HS_LOG_DIR");
        if (configured == null || configured.isBlank()) {
            configured = System.getProperty("HS_LOG_DIR", "logs");
        }
        return Path.of(configured);
    }
}
