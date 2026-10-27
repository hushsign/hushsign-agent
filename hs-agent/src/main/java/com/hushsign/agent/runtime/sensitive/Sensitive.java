package com.hushsign.agent.runtime.sensitive;

import java.util.Objects;

/**
 * A string that identifies a person or a subscription — an IMSI, an MSISDN, an
 * ICCID — and which must therefore not reach a log line unless the caller asks
 * for it by name (P7-S5).
 *
 * <p>{@link #toString()} is deliberately the <em>masked</em> form, so a value of
 * this type drops into a log statement, an exception message or a
 * {@code String.format} and still comes out clean. The raw value is reachable
 * only through {@link #reveal()}, which is a visible and greppable call at the
 * one or two places that genuinely need it.
 *
 * <p>Deliberately not a {@link CharSequence}: {@code StringBuilder.append} would
 * pick up the raw characters through that interface and quietly undo the whole
 * point of the class.
 */
public final class Sensitive {

    /** How many leading characters survive masking. */
    private static final int VISIBLE_PREFIX = 5;

    private static final Sensitive EMPTY = new Sensitive("");

    private final String value;

    private Sensitive(String value) {
        this.value = value;
    }

    /** Wraps a value; null becomes the empty instance rather than failing. */
    public static Sensitive of(String value) {
        return value == null ? EMPTY : new Sensitive(value);
    }

    public static Sensitive empty() {
        return EMPTY;
    }

    /** The raw value. Every call site is a decision, so keep them countable. */
    public String reveal() {
        return this.value;
    }

    /** The log-safe rendering: a prefix that is not identifying, then asterisks. */
    public String masked() {
        return mask(this.value);
    }

    public boolean isPresent() {
        return !this.value.isEmpty();
    }

    @Override
    public String toString() {
        return masked();
    }

    /**
     * Masks a raw string. Five characters survive: for an IMSI that is the MCC
     * plus MNC (the network, not the subscriber) and for an MSISDN it is the
     * country code plus the leading digits — enough to recognise the shape of a
     * problem, never enough to identify who it was.
     */
    public static String mask(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        if (raw.length() <= VISIBLE_PREFIX) {
            return "*".repeat(raw.length());
        }
        return raw.substring(0, VISIBLE_PREFIX) + "*".repeat(raw.length() - VISIBLE_PREFIX);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Sensitive sensitive && this.value.equals(sensitive.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.value);
    }
}
