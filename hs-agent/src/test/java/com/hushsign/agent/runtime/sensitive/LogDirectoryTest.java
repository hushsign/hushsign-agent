package com.hushsign.agent.runtime.sensitive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * P7-S5: {@code agent.log} carries raw SIM identities, so the directory holding it is narrowed to
 * the account that runs the agent.
 */
class LogDirectoryTest {

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void hardeningMakesTheDirectoryOwnerOnly() throws IOException {
        Path dir = Files.createTempDirectory("hs-logdir").resolve("logs");

        assertTrue(LogDirectory.harden(dir));
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE), Files.getPosixFilePermissions(dir));
    }

    @Test
    void hardeningCreatesTheDirectoryItWasGiven() throws IOException {
        Path dir = Files.createTempDirectory("hs-logdir-created").resolve("nested").resolve("logs");

        LogDirectory.harden(dir);

        assertTrue(Files.isDirectory(dir));
    }

    @Test
    void nothingToHardenIsNotAFailure() {
        assertFalse(LogDirectory.harden(null));
    }
}
