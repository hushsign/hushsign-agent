package com.hushsign.agent.simulator;

import com.hushsign.agent.gsm.modem.Modem;
import com.hushsign.agent.gsm.modem.ModemConfig;
import com.hushsign.agent.gsm.modem.ModemException;
import com.hushsign.agent.gsm.modem.ModemState;
import com.hushsign.agent.technique.Technique;
import com.hushsign.agent.technique.TechniqueParams;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-S5 tests: the mock modem drives the real hs-gsm modem layer through the
 * four plan scenarios (success, failure, offline, policy reject) and captures
 * the technique PDUs byte-for-byte.
 */
class SimModemTest {

    private static final String DESTINATION = "40756270541";

    private static ModemConfig fastConfig() {
        return ModemConfig.builder("sim-test")
                .responseTimeoutMs(400)
                .commandWaitMs(10)
                .pollWaitMs(10)
                .probeAttempts(2)
                .probeRetryDelayMs(10)
                .maxConsecutiveErrors(3)
                .build();
    }

    @Test
    void probeIdentifiesTheSimulatedModem() throws Exception {
        SimModem sim = new SimModem("SIM1", SimScenario.SUCCESS, "359876543210987");

        try (Modem modem = Modem.open(sim, fastConfig())) {
            assertEquals(ModemState.READY, modem.state());
            assertEquals("SIM1", modem.portName());
            assertEquals("359876543210987", modem.getDeviceInformation().getSerialNo());
            assertEquals(115200, sim.configuredBaudRate());
            assertEquals(400, sim.configuredReadTimeoutMs());
            assertTrue(sim.sentText().startsWith("AT\rAT+CGSN\r"), "probe handshake: " + sim.sentText());
        }
    }

    @Test
    void successScenarioReturnsIndexesAndCapturesTheTechniquePdu() throws Exception {
        SimModem sim = new SimModem("SIM2", SimScenario.SUCCESS);
        String pduHex = Technique.SILENT_TP0.generate(DESTINATION, null);

        try (Modem modem = Modem.open(sim, fastConfig())) {
            assertEquals(1, modem.sendPdu(pduHex));
            assertEquals(2, modem.sendPdu(pduHex));
            assertEquals(ModemState.READY, modem.state());
        }

        assertEquals(List.of(pduHex, pduHex), sim.receivedPdus(),
                "the simulator must capture the exact PDU bytes the agent sends");
        assertTrue(sim.sentText().contains("AT+CMGS=" + pduHex.length() / 2 + "\r" + pduHex),
                "wire must carry the PDU");
    }

    @Test
    void failureScenarioDegradesThenQuarantinesTheModem() throws Exception {
        SimModem sim = new SimModem("SIM3", SimScenario.FAILURE);
        String pduHex = Technique.SILENT_TP0.generate(DESTINATION, null);

        try (Modem modem = Modem.open(sim, fastConfig())) {
            assertThrows(ModemException.class, () -> modem.sendPdu(pduHex));
            assertEquals(ModemState.DEGRADED, modem.state());
            assertThrows(ModemException.class, () -> modem.sendPdu(pduHex));
            assertEquals(ModemState.DEGRADED, modem.state());
            assertThrows(ModemException.class, () -> modem.sendPdu(pduHex));
            assertEquals(ModemState.ERROR, modem.state(), "three consecutive failures quarantine the modem");
            assertThrows(ModemException.class, () -> modem.sendPdu(pduHex),
                    "quarantined modems refuse sends");
        }
        assertEquals(List.of(pduHex, pduHex, pduHex), sim.receivedPdus());
    }

    @Test
    void offlineScenarioProbesButSendsTimeOut() throws Exception {
        // the AT surface answers (probe succeeds), the PDU response never comes
        SimModem offline = new SimModem("SIM4", SimScenario.OFFLINE);
        try (Modem modem = Modem.open(offline, fastConfig())) {
            assertEquals(ModemState.READY, modem.state());
            assertThrows(ModemException.class,
                    () -> modem.sendPdu(Technique.SILENT_TP0.generate(DESTINATION, null)),
                    "a silent PDU response times out");
            assertEquals(ModemState.DEGRADED, modem.state());
        }

        // mid-run transition: online first, then the radio dies
        SimModem sim = new SimModem("SIM5", SimScenario.SUCCESS);
        try (Modem modem = Modem.open(sim, fastConfig())) {
            assertEquals(1, modem.sendPdu(Technique.SILENT_TP0.generate(DESTINATION, null)));
            sim.scenario(SimScenario.OFFLINE);
            assertThrows(ModemException.class,
                    () -> modem.sendPdu(Technique.SILENT_TP0.generate(DESTINATION, null)));
            assertEquals(ModemState.DEGRADED, modem.state(), "a timed-out send degrades the modem");
        }
    }

    @Test
    void policyRejectScenarioReturnsCmsError304() throws Exception {
        SimModem sim = new SimModem("SIM6", SimScenario.POLICY_REJECT);

        try (Modem modem = Modem.open(sim, fastConfig())) {
            assertThrows(ModemException.class,
                    () -> modem.sendPdu(Technique.SILENT_TP0.generate(DESTINATION, null)));
            assertEquals(ModemState.DEGRADED, modem.state());
        }
    }

    @Test
    void scenarioCanBeReplayedAndRecoveredMidRun() throws Exception {
        SimModem sim = new SimModem("SIM7", SimScenario.SUCCESS);
        String pduHex = Technique.SILENT_TP0.generate(DESTINATION, null);

        try (Modem modem = Modem.open(sim, fastConfig())) {
            assertEquals(1, modem.sendPdu(pduHex));

            sim.scenario(SimScenario.FAILURE);
            assertThrows(ModemException.class, () -> modem.sendPdu(pduHex));
            assertEquals(ModemState.DEGRADED, modem.state());

            sim.scenario(SimScenario.SUCCESS);
            assertEquals(3, modem.sendPdu(pduHex), "message index keeps counting across scenarios");
            assertEquals(ModemState.READY, modem.state(), "one success resets the error counter");
        }
        assertEquals(List.of(pduHex, pduHex, pduHex), sim.receivedPdus());
    }

    @Test
    void refreshDeviceInformationPopulatesTheWholeIdentitySurface() throws Exception {
        SimModem sim = new SimModem("SIM8", SimScenario.SUCCESS);

        try (Modem modem = Modem.open(sim, fastConfig())) {
            modem.refreshDeviceInformation();
        }

        String tx = sim.sentText();
        assertTrue(tx.contains("AT+CGMI\r"), "manufacturer: " + tx);
        assertTrue(tx.contains("AT+CGMM\r"), "model: " + tx);
        assertTrue(tx.contains("AT+CIMI\r"), "IMSI: " + tx);
        assertTrue(tx.contains("AT+CGMR\r"), "SW version: " + tx);
        assertTrue(tx.contains("AT+CSQ\r"), "signal: " + tx);
    }

    @Test
    void everyTechniqueSurvivesTheSimulatedHappyPath() throws Exception {
        SimModem sim = new SimModem("SIM9", SimScenario.SUCCESS);
        try (Modem modem = Modem.open(sim, fastConfig())) {
            modem.sendPdu(Technique.SILENT_TP0.generate(DESTINATION, null));
            modem.sendPdu(Technique.WAP_PUSH_EMPTY.generate(DESTINATION, null));
            modem.sendPdu(Technique.WAP_PUSH_SL.generate(DESTINATION,
                    new TechniqueParams.WapPush(TechniqueParams.WapScheme.HTTPS)));
            modem.sendPdu(Technique.WAP_PUSH_SI.generate(DESTINATION,
                    new TechniqueParams.WapPush(TechniqueParams.WapScheme.HTTP)));
            modem.sendPdu(Technique.MWI_TOGGLE.generate(DESTINATION,
                    new TechniqueParams.MwiToggle(true, 123)));
            modem.sendPdu(Technique.MMS_NOTIFY_EMPTY.generate(DESTINATION, null));
            assertEquals(ModemState.READY, modem.state());
        }
        assertEquals(6, sim.receivedPdus().size());
        assertEquals(Technique.WAP_PUSH_SL.generate(DESTINATION,
                        new TechniqueParams.WapPush(TechniqueParams.WapScheme.HTTPS)),
                sim.receivedPdus().get(2));
    }
}
