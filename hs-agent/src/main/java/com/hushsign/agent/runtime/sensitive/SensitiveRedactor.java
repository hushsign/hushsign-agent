package com.hushsign.agent.runtime.sensitive;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The last line of defence for the ship-safe log: whatever a caller wrote, an
 * IMSI or an MSISDN that slipped through as a plain string is masked before the
 * line is written.
 *
 * <p>This exists because {@link Sensitive} only protects values that were put
 * into it. A policy reason such as {@code "target +40700000001 has reached its
 * cap"}, a stack trace, or a third-party library's own message arrive as
 * ordinary text, and those are exactly the lines that end up in a support
 * bundle. The clean log is therefore scrubbed <em>after</em> formatting, not
 * before.
 */
public final class SensitiveRedactor {

    /**
     * A run of 11 to 20 digits, optionally led by {@code +}, or a national-format number (a
     * leading zero and nine more digits). A {@code 07...} number is only ten digits, so it used
     * to survive in clear even though it names the same subscriber as {@code +407...}.
     * Shorter runs are ports, counters and durations. Longer runs are hashes and identifiers —
     * although a hex digest can still contain a 20-digit run, and it is masked
     * when it does: the clean log is the one that leaves the device, and the
     * unscrubbed original is always still in {@code agent.log} and in the audit
     * JSONL, so a redacted hash costs nothing diagnosable.
     *
     * <p>Twenty is the ceiling because an ICCID is up to 20 digits, and an ICCID
     * identifies a SIM just as surely as an IMSI does.
     */
    private static final Pattern SUBSCRIBER_NUMBER = Pattern.compile(
            "(?<!\\d)(\\+?\\d{11,20}|0\\d{9})(?!\\d)");

    private SensitiveRedactor() {
    }

    /** Returns the text with subscriber numbers masked; never null. */
    public static String scrub(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher matcher = SUBSCRIBER_NUMBER.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(Sensitive.mask(matcher.group(1))));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** Whether the text still carries something that looks like an identity. */
    public static boolean containsIdentity(String text) {
        return text != null && SUBSCRIBER_NUMBER.matcher(text).find();
    }
}
