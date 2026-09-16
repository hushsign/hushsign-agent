package com.hushsign.agent.gsm.modem;

/** A modem operation failed (open, probe, send). */
public class ModemException extends Exception {

    public ModemException(String message) {
        super(message);
    }

    public ModemException(String message, Throwable cause) {
        super(message, cause);
    }
}
