package com.hushsign.agent.runtime.sensitive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * P7-S5: the shipped {@code logback.xml}, loaded exactly as the agent loads it, must produce one
 * log that is safe to hand over and one that is not.
 *
 * <p>This is the assertion the whole story exists for. A redaction helper that nobody wired in is
 * worthless, and a clean log that is clean only because the call sites remembered to mask is a
 * promise rather than a guarantee — so the test writes a raw IMSI and a policy reason containing
 * a raw MSISDN through the real configuration and then reads both files back.
 */
class DualLogSeparationTest {

    private static final String IMSI = "310150123456789";
    private static final String MSISDN = "+40756270541";

    @Test
    void theShipSafeLogCarriesNoSubscriberIdentity() throws Exception {
        Path dir = Files.createTempDirectory("hs-dual-log");

        runThroughTheShippedConfig(dir, logger -> {
            logger.info("modem claimed: serial={} imsi={}", "ABC123", IMSI);
            logger.warn("policy rejected emission e-1: target {} has reached its 6-per-hour cap", MSISDN);
            logger.info("a plain line with a count of 42");
        });

        String clean = Files.readString(dir.resolve("agent-clean.log"), StandardCharsets.UTF_8);
        String full = Files.readString(dir.resolve("agent.log"), StandardCharsets.UTF_8);

        assertTrue(full.contains(IMSI), "the operator's own log keeps the raw IMSI");
        assertTrue(full.contains(MSISDN), "the operator's own log keeps the raw target");
        assertFalse(clean.contains(IMSI), "the ship-safe log must not carry an IMSI");
        assertFalse(clean.contains(MSISDN), "the ship-safe log must not carry an MSISDN");
        assertTrue(clean.contains("31015**********"), "the masked IMSI stays correlatable");
        assertTrue(clean.contains("+4075*******"), "the masked target stays correlatable");
        assertTrue(clean.contains("a plain line with a count of 42"), "ordinary lines pass through");
    }

    @Test
    void nothingInTheShipSafeLogLooksLikeASubscriberNumber() throws Exception {
        Path dir = Files.createTempDirectory("hs-dual-log-scan");

        runThroughTheShippedConfig(dir, logger -> {
            logger.info("iccid={} imsi={} msisdn={}", "8944010000000000000", IMSI, MSISDN);
            logger.error("failed to reach {} while sending to {}", "kafka:9092", "+40700000001");
        });

        String clean = Files.readString(dir.resolve("agent-clean.log"), StandardCharsets.UTF_8);

        assertFalse(clean.contains("8944010000000000000"), "an ICCID is a SIM identity as well");
        assertFalse(clean.contains("40700000001"));
        assertFalse(SensitiveRedactor.containsIdentity(clean),
                "no 11-20 digit run may survive in a log that leaves the device: " + clean);
    }

    @Test
    void byDefaultTheRawLogIsNotWrittenAtAll() throws Exception {
        Path dir = Files.createTempDirectory("hs-masked-log");

        runThroughTheShippedConfig(dir, IdentifierLogging.MASKED, logger -> {
            logger.info("modem claimed: serial={} imsi={}", "ABC123", IMSI);
            logger.warn("policy rejected emission e-1: target {} has reached its 6-per-hour cap", MSISDN);
        });

        String clean = Files.readString(dir.resolve("agent-clean.log"), StandardCharsets.UTF_8);
        Path raw = dir.resolve("agent.log");
        String full = Files.exists(raw) ? Files.readString(raw, StandardCharsets.UTF_8) : "";

        assertEquals("", full, "masked means agent.log is not written, not merely scrubbed");
        assertTrue(clean.contains("31015**********"), "the clean log still carries the correlatable mask");
        assertFalse(SensitiveRedactor.containsIdentity(clean), "and no identity: " + clean);
    }

    @Test
    void anUnreadableSettingFailsInsteadOfFallingBackSilently() {
        String previous = System.getProperty(IdentifierLogging.ENV);
        System.setProperty(IdentifierLogging.ENV, "RAW");   // a typo an operator will make
        try {
            assertEquals(IdentifierLogging.RAW, IdentifierLogging.configured(), "case does not matter");

            System.setProperty(IdentifierLogging.ENV, "unmasked");
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    IdentifierLogging::configured);
            assertTrue(thrown.getMessage().contains(IdentifierLogging.ENV), thrown.getMessage());
            assertFalse(IdentifierLogging.rawEnabled(), "the filter fails closed while the value is wrong");
        } finally {
            if (previous == null) {
                System.clearProperty(IdentifierLogging.ENV);
            } else {
                System.setProperty(IdentifierLogging.ENV, previous);
            }
        }
    }

    private interface LogWork {
        void log(Logger logger);
    }

    /**
     * Configures the real factory context rather than a private one: a bare
     * {@code new LoggerContext()} has no MDC adapter, and every appender then
     * throws and drops the event — which reads as "redaction works" because the
     * files are empty. Resetting afterwards detaches and stops the appenders,
     * which is also what flushes them, so the rest of the suite sees no change
     * except a quiet logger.
     */
    private void runThroughTheShippedConfig(Path dir, LogWork work) throws Exception {
        runThroughTheShippedConfig(dir, IdentifierLogging.RAW, work);
    }

    /**
     * @param identifiers {@link IdentifierLogging#RAW} or {@link IdentifierLogging#MASKED} -
     *                    P7-S12 made the raw file opt-in, so a test that expects raw values in
     *                    {@code agent.log} has to ask for them the way an operator would
     */
    private void runThroughTheShippedConfig(Path dir, String identifiers, LogWork work) throws Exception {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        String previous = System.getProperty("HS_LOG_DIR");
        String previousIdentifiers = System.getProperty(IdentifierLogging.ENV);
        System.setProperty("HS_LOG_DIR", dir.toString());
        System.setProperty(IdentifierLogging.ENV, identifiers);
        try {
            context.reset();
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            try (InputStream spec = getClass().getResourceAsStream("/logback.xml")) {
                assertNotNull(spec, "the shipped logback.xml must be on the classpath");
                configurator.doConfigure(spec);
            }
            work.log(context.getLogger("com.hushsign.agent.dual.test"));
        } finally {
            context.reset();
            if (previous == null) {
                System.clearProperty("HS_LOG_DIR");
            } else {
                System.setProperty("HS_LOG_DIR", previous);
            }
            if (previousIdentifiers == null) {
                System.clearProperty(IdentifierLogging.ENV);
            } else {
                System.setProperty(IdentifierLogging.ENV, previousIdentifiers);
            }
        }
    }
}
