package com.hushsign.agent.simulator;

import com.hushsign.agent.gsm.modem.Modem;
import com.hushsign.agent.gsm.modem.ModemConfig;
import com.hushsign.agent.gsm.modem.ModemException;
import com.hushsign.agent.gsm.modem.ModemState;
import com.hushsign.agent.technique.Technique;
import com.hushsign.agent.technique.TechniqueParams;

/**
 * Demo runner (P1-S5): drives one technique through a {@link SimModem} exactly
 * like the future agent will, printing the AT exchange and the outcome.
 *
 * <p>Usage: {@code java ... SimulatorApp [SUCCESS|FAILURE|OFFLINE|POLICY_REJECT]
 * [SILENT_TP0|WAP_PUSH_EMPTY|WAP_PUSH_SL|WAP_PUSH_SI|MWI_TOGGLE|MMS_NOTIFY_EMPTY]
 * [destination-digits]}
 */
public final class SimulatorApp {

    private SimulatorApp() {
    }

    public static void main(String[] args) throws Exception {
        SimScenario scenario = args.length > 0 ? SimScenario.valueOf(args[0].toUpperCase()) : SimScenario.SUCCESS;
        Technique technique = args.length > 1 ? Technique.valueOf(args[1].toUpperCase()) : Technique.SILENT_TP0;
        String destination = args.length > 2 ? args[2] : "40756270541";

        SimModem sim = new SimModem("SIM0", scenario);
        ModemConfig config = ModemConfig.builder("sim-gw")
                .responseTimeoutMs(1500)
                .commandWaitMs(50)
                .pollWaitMs(50)
                .probeAttempts(2)
                .probeRetryDelayMs(100)
                .maxConsecutiveErrors(3)
                .build();

        System.out.println("== HushSign simulator: " + technique.code() + " -> " + destination
                + " (scenario " + scenario + ") ==");
        try (Modem modem = Modem.open(sim, config)) {
            String pduHex = technique.generate(destination, defaultParams(technique));
            System.out.println("PDU: " + pduHex);
            int index = modem.sendPdu(pduHex);
            System.out.println("sent OK, modem message index " + index + ", state " + modem.state());
        } catch (ModemException e) {
            System.out.println("send failed (" + e.getMessage() + ")");
        }
        System.out.println("--- wire ---");
        System.out.println(sim.sentText().replace("\r", "\\r"));
        System.out.println("captured PDUs: " + sim.receivedPdus());
        if (scenario == SimScenario.OFFLINE) {
            System.out.println("note: OFFLINE answers AT but never completes PDU sends (timeout)");
        }
    }

    private static TechniqueParams defaultParams(Technique technique) {
        return switch (technique) {
            case SILENT_TP0, WAP_PUSH_EMPTY, MMS_NOTIFY_EMPTY -> TechniqueParams.None.INSTANCE;
            case WAP_PUSH_SL, WAP_PUSH_SI ->
                    new TechniqueParams.WapPush(TechniqueParams.WapScheme.NONE);
            case MWI_TOGGLE -> new TechniqueParams.MwiToggle(true, 1);
        };
    }
}
