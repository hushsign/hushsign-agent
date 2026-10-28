package org.smslib.gateway.modem.driver;

import com.hushsign.agent.gsm.modem.ModemContext;
import org.junit.jupiter.api.Test;
import org.smslib.core.Capabilities;
import org.smslib.gateway.modem.DeviceInformation;
import org.smslib.gateway.modem.ModemResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-S1 smoke tests for the vendored AT layer, decoupled from the Service
 * machinery via the ModemContext seam. P1-S2 supplies the real serial I/O.
 */
class AtLayerSmokeTest {

    private static class FakeModemContext implements ModemContext {
        private final DeviceInformation deviceInformation = new DeviceInformation();

        @Override
        public DeviceInformation getDeviceInformation() {
            return deviceInformation;
        }

        @Override
        public String getGatewayId() {
            return "gw-test";
        }

        @Override
        public String getSimPin() {
            return null;
        }

        @Override
        public String getSimPin2() {
            return null;
        }

        @Override
        public void setCapabilities(Capabilities caps) {
            // The AT layer hands its capability flags back here; nothing in this test asserts
            // them, and storing them would be state the test never reads.
        }
    }

    private static class FakeDriver extends AbstractModemDriver {
        final ByteArrayOutputStream sent = new ByteArrayOutputStream();

        FakeDriver() {
            super(new FakeModemContext());
        }

        @Override
        public void openPort() {
            // No real port: the driver talks to the StringBuilder in `sent`.
        }

        @Override
        public void closePort() {
            // Nothing was opened.
        }

        @Override
        public String getPortInfo() {
            return "fake";
        }

        @Override
        protected void write(byte[] s) throws IOException {
            sent.write(s);
        }

        @Override
        protected void write(byte s) throws IOException {
            sent.write(s);
        }
    }

    @Test
    void writeCollectsModemResponse() throws Exception {
        FakeDriver driver = new FakeDriver();

        var responseHolder = new java.util.concurrent.atomic.AtomicReference<ModemResponse>();
        var errorHolder = new java.util.concurrent.atomic.AtomicReference<Throwable>();

        Thread responder = new Thread(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            driver.buffer.append("OK\r\n");
        });

        Thread command = new Thread(() -> {
            try {
                responseHolder.set(driver.write("AT\r", false));
            } catch (Throwable t) {
                errorHolder.set(t);
            }
        });

        responder.start();
        command.start();
        responder.join(5_000);
        command.join(10_000);

        assertTrue(!command.isAlive(), "write() must complete once the response is fed");
        if (errorHolder.get() != null) {
            throw new AssertionError("write() failed", errorHolder.get());
        }
        // Upstream semantics: "OK"/"ERROR" are terminators and set the responseOk
        // flag only; informative lines (e.g. "+CMGS: n") form the response data.
        ModemResponse response = responseHolder.get();
        assertTrue(response.isResponseOk());
        assertEquals("", response.getResponseData());
        assertEquals("AT\r", driver.sent.toString(StandardCharsets.UTF_8));
    }

    @Test
    void skipResponseWriteDoesNotBlock() throws Exception {
        FakeDriver driver = new FakeDriver();
        ModemResponse response = driver.write("AT+CMGF=0\r", true);
        assertTrue(response.isResponseOk());
        assertEquals("", response.getResponseData());
        assertEquals("AT+CMGF=0\r", driver.sent.toString(StandardCharsets.UTF_8));
    }

    @Test
    void vendoredModemPropertiesProvideDefaults() throws IOException {
        FakeDriver driver = new FakeDriver();
        assertEquals("700", driver.getModemSettings("command_wait_unit"));
        assertEquals("30000", driver.getModemSettings("timeout"));
    }
}
