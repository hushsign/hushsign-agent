package com.hushsign.agent.simulator;

import com.hushsign.agent.gsm.modem.Modem;
import com.hushsign.agent.gsm.modem.ModemConfig;
import com.hushsign.agent.gsm.modem.ModemException;
import com.hushsign.agent.gsm.modem.PduSubmission;
import com.hushsign.agent.gsm.serial.JSerialCommPortFactory;
import com.hushsign.agent.technique.Technique;
import com.hushsign.agent.technique.TechniqueParams;
import org.smslib.gateway.modem.DeviceInformation;

/**
 * P7-S9 field probe: opens a REAL modem and reports what it found, without
 * transmitting anything unless explicitly told to.
 *
 * <p>Sending an SMS to a number nobody chose is not a test, it is an incident, so
 * this tool is read-only by default: it prints the device information, the state
 * and the PDU it <em>would</em> send, then closes the port. Pass
 * {@code --send=<digits>} to actually submit one SILENT_TP0 to a number you own.
 *
 * <p>Usage:
 * <pre>
 *   java -cp ... RealModemProbe [serial-port] [--technique=NAME] [--send=digits]
 * </pre>
 * Defaults: {@code /dev/ttyUSB0}, {@code SILENT_TP0}, no transmission. Run as root
 * or as a member of {@code dialout}.
 */
public final class RealModemProbe {

    private RealModemProbe() {
    }

    public static void main(String[] args) throws Exception {
        String port = "/dev/ttyUSB0";
        String techniqueName = "SILENT_TP0";
        String destination = null;
        for (String arg : args) {
            if (arg.startsWith("--technique=")) {
                techniqueName = arg.substring("--technique=".length());
            } else if (arg.startsWith("--send=")) {
                destination = arg.substring("--send=".length());
            } else if (!arg.startsWith("--")) {
                port = arg;
            }
        }

        System.out.println("== HushSign modem probe");
        System.out.println("port       : " + port);
        System.out.println("technique  : " + techniqueName);
        System.out.println("transmit   : " + (destination == null ? "NO (read-only)" : "YES -> " + destination));

        ModemConfig config = ModemConfig.builder("field-probe")
                .baudRate(115200)
                .responseTimeoutMs(10_000)
                .commandWaitMs(500)
                .pollWaitMs(100)
                .probeAttempts(3)
                .probeRetryDelayMs(500)
                .maxConsecutiveErrors(3)
                // The Huawei K3765 family rejects the full PDU form with
                // +CMS ERROR: 304 and wants TPDU-only hex submission.
                .pduSubmission(PduSubmission.TPDU_HEX)
                .build();

        try (Modem modem = Modem.open(new JSerialCommPortFactory().open(port), config)) {
            System.out.println("state      : " + modem.state());
            modem.refreshDeviceInformation();
            DeviceInformation info = modem.getDeviceInformation();
            if (info == null) {
                System.out.println("device     : (no response to the identity commands)");
            } else {
                System.out.println("manufacturer: " + info.getManufacturer());
                System.out.println("model       : " + info.getModel());
                System.out.println("firmware    : " + info.getSwVersion());
                System.out.println("serial/imei : " + info.getSerialNo());
                System.out.println("imsi        : " + mask(info.getImsi()));
                System.out.println("signal rssi : " + info.getRssi());
                System.out.println("network mode: " + info.getMode());
            }

            Technique technique = Technique.valueOf(techniqueName);
            String sample = destination == null ? "400000000000" : destination;
            String pdu = technique.generate(sample, TechniqueParams.None.INSTANCE);
            System.out.println("PDU sample : " + pdu + " (" + pdu.length() / 2 + " octets, destination "
                    + (destination == null ? "placeholder" : "the one you passed") + ")");

            if (destination == null) {
                System.out.println("PROBE RESULT: hardware OK, nothing transmitted");
                return;
            }
            int index = modem.sendPdu(pdu);
            System.out.println("PROBE RESULT: submitted, modem message index=" + index
                    + ", state=" + modem.state());
        } catch (ModemException e) {
            System.out.println("PROBE RESULT: FAILED - " + e.getMessage());
            System.exit(2);
        }
    }

    /** Never print a full IMSI: it identifies the subscriber. */
    private static String mask(String imsi) {
        if (imsi == null || imsi.length() < 6) {
            return "-";
        }
        return imsi.substring(0, 5) + "*".repeat(Math.max(0, imsi.length() - 5));
    }
}