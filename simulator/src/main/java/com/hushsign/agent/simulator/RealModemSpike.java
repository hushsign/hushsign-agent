package com.hushsign.agent.simulator;

import com.hushsign.agent.gsm.modem.Modem;
import com.hushsign.agent.gsm.modem.ModemConfig;
import com.hushsign.agent.gsm.modem.ModemException;
import com.hushsign.agent.gsm.serial.JSerialCommPortFactory;
import com.hushsign.agent.gsm.serial.SerialPort;
import com.hushsign.agent.technique.Technique;
import com.hushsign.agent.technique.TechniqueParams;

/**
 * P1-S14 spike: drives a REAL modem through the hs-gsm stack (jSerialComm →
 * vendored AT layer → technique PDU) and sends one SILENT_TP0.
 *
 * <p>Usage: {@code java -cp ... com.hushsign.agent.simulator.RealModemSpike
 * [serial-port] [destination-digits]} — defaults {@code /dev/ttyUSB0} and a
 * placeholder destination. Run as root (or a dialout member) on the Pi.
 */
public final class RealModemSpike {

    private RealModemSpike() {
    }

    public static void main(String[] args) throws Exception {
        String port = args.length > 0 ? args[0] : "/dev/ttyUSB0";
        String destination = args.length > 1 ? args[1] : "40756270541";

        System.out.println("== P1-S14 real modem spike: " + port + " -> " + destination);

        ModemConfig config = ModemConfig.builder("pi-gw-01")
                .baudRate(115200)
                .responseTimeoutMs(10_000)
                .commandWaitMs(500)
                .pollWaitMs(100)
                .probeAttempts(3)
                .probeRetryDelayMs(500)
                .maxConsecutiveErrors(3)
                .build();

        SerialPort serial = new JSerialCommPortFactory().open(port);
        try (Modem modem = Modem.open(serial, config)) {
            System.out.println("modem open on " + port + ", state=" + modem.state());

            String pdu = Technique.SILENT_TP0.generate(destination, TechniqueParams.None.INSTANCE);
            System.out.println("SILENT_TP0 PDU (" + pdu.length() / 2 + " octets): " + pdu);

            int index = modem.sendPdu(pdu);
            System.out.println("SPIKE RESULT: sent OK, modem message index=" + index
                    + ", state=" + modem.state());
        } catch (ModemException e) {
            System.out.println("SPIKE RESULT: FAILED - " + e.getMessage());
            System.exit(2);
        }
    }
}
