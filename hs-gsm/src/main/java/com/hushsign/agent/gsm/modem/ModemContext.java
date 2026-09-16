package com.hushsign.agent.gsm.modem;

import org.smslib.core.Capabilities;
import org.smslib.gateway.modem.DeviceInformation;

/**
 * The minimal modem state the vendored AT layer needs (P1-S1).
 *
 * <p>The original SMSLib {@code AbstractModemDriver} was constructed with the
 * whole {@code Modem} aggregate (gateway, coverage, credit balance, message
 * model). The vendored AT layer only reads device information, capabilities and
 * the SIM PINs — this interface is the seam, implemented by the fresh modem
 * layer in P1-S2.
 */
public interface ModemContext {

    DeviceInformation getDeviceInformation();

    String getGatewayId();

    String getSimPin();

    String getSimPin2();

    void setCapabilities(Capabilities capabilities);
}
