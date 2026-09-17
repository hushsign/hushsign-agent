package com.hushsign.agent.config;

/**
 * Unchecked configuration error: a missing file, a malformed YAML value, an
 * unknown key or an invalid value. The agent refuses to start with a broken
 * config — fail fast, never guess.
 */
public class ConfigException extends RuntimeException {

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
