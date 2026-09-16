package com.hushsign.protocol;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Keeps the committed {@code schemas.sha256} honest: every schema resource must
 * appear in the manifest with its current SHA-256, and the manifest must contain
 * exactly the canonical schema set. The manifest is what the agent repo's drift
 * check (P1-S12) compares its vendored copy against.
 */
class SchemaManifestTest {

    private static final String MANIFEST = "/protocol/schemas/schemas.sha256";

    @Test
    void manifestMatchesSchemaResources() throws Exception {
        Map<String, String> expected = readManifest();
        assertEquals(MessageType.values().length, expected.size(),
                "manifest must cover exactly one entry per message type");

        for (MessageType type : MessageType.values()) {
            String relPath = "v1/" + type.schemaName();
            String actualHash = sha256("/protocol/schemas/" + relPath);
            assertEquals(expected.get(relPath), actualHash,
                    "schema changed without regenerating schemas.sha256: " + relPath);
        }
    }

    private static Map<String, String> readManifest() throws Exception {
        Map<String, String> entries = new HashMap<>();
        try (InputStream in = SchemaManifestTest.class.getResourceAsStream(MANIFEST)) {
            assertNotNull(in, "manifest not found on classpath: " + MANIFEST);
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split(" {2}");
                assertEquals(2, parts.length, "manifest line must be '<sha256>  <path>': " + line);
                entries.put(parts[1], parts[0]);
            }
        }
        return entries;
    }

    private static String sha256(String resource) throws Exception {
        try (InputStream in = SchemaManifestTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "schema not found on classpath: " + resource);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) != -1) {
                digest.update(buf, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        }
    }
}
