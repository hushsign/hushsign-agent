package com.hushsign.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Test helper loading golden payloads from {@code protocol/golden/}. */
final class GoldenPayloads {

    private GoldenPayloads() {
    }

    static String load(String fileName) {
        String resource = "/protocol/golden/" + fileName;
        try (InputStream in = GoldenPayloads.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("golden payload not found: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + resource, e);
        }
    }
}
