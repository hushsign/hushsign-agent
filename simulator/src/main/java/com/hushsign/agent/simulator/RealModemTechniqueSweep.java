package com.hushsign.agent.simulator;

import com.hushsign.agent.gsm.modem.Modem;
import com.hushsign.agent.gsm.modem.ModemConfig;
import com.hushsign.agent.gsm.modem.ModemException;
import com.hushsign.agent.gsm.modem.PduSubmission;
import com.hushsign.agent.gsm.serial.JSerialCommPortFactory;
import com.hushsign.agent.technique.Technique;
import com.hushsign.agent.technique.TechniqueParams;

/**
 * P1-S14 field sweep: sends EVERY catalog technique through a real modem to
 * one destination, with the K3765 TPDU-hex submission mode.
 *
 * <p>Usage: {@code java -cp ... com.hushsign.agent.simulator.RealModemTechniqueSweep
 * [serial-port] [destination-digits]}.
 */
public final class RealModemTechniqueSweep {

    private RealModemTechniqueSweep() {
    }

    public static void main(String[] args) throws Exception {
        String port = args.length > 0 ? args[0] : "COM6";
        String destination = args.length > 1 ? args[1] : "40756270541";
        boolean mwiOff = args.length > 2 && args[2].equalsIgnoreCase("MWI_OFF");

        ModemConfig config = ModemConfig.builder("pi-gw-01")
                .baudRate(115200)
                .responseTimeoutMs(10_000)
                .commandWaitMs(500)
                .pollWaitMs(100)
                .probeAttempts(3)
                .probeRetryDelayMs(500)
                .maxConsecutiveErrors(3)
                // P1-S14: Huawei K3765 family requires TPDU-only hex submission.
                .pduSubmission(PduSubmission.TPDU_HEX)
                .build();

        try (Modem modem = Modem.open(new JSerialCommPortFactory().open(port), config)) {
            System.out.println("modem open on " + port + ", state=" + modem.state());
            if (mwiOff) {
                String pdu = Technique.MWI_TOGGLE.generate(destination,
                        new TechniqueParams.MwiToggle(false, 1));
                int index = modem.sendPdu(pdu);
                System.out.println("MWI_TOGGLE(deactivate) PDU (" + pdu.length() / 2
                        + " octets): " + pdu + " -> index=" + index);
                System.out.println("SWEEP RESULT: MWI deactivation sent, state=" + modem.state());
                return;
            }
            int sent = 0;
            for (Technique technique : Technique.values()) {
                String pdu = technique.generate(destination, defaultParams(technique));
                int index = modem.sendPdu(pdu);
                System.out.println(technique + " PDU (" + pdu.length() / 2 + " octets): "
                        + pdu + " -> index=" + index);
                sent++;
                Thread.sleep(500);
            }
            System.out.println("SWEEP RESULT: sent OK, " + sent + " techniques, state="
                    + modem.state());
        } catch (ModemException e) {
            System.out.println("SWEEP RESULT: FAILED - " + e.getMessage());
            System.exit(2);
        }
    }

    private static TechniqueParams defaultParams(Technique technique) {
        return switch (technique) {
            case WAP_PUSH_SL -> new TechniqueParams.WapPush(TechniqueParams.WapScheme.HTTPS);
            case WAP_PUSH_SI -> new TechniqueParams.WapPush(TechniqueParams.WapScheme.HTTP);
            case MWI_TOGGLE -> new TechniqueParams.MwiToggle(true, 1);
            default -> TechniqueParams.None.INSTANCE;
        };
    }
}
