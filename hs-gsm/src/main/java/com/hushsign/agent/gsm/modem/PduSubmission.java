package com.hushsign.agent.gsm.modem;

/**
 * How the AT layer hands a PDU to {@code AT+CMGS} (P1-S14 field finding).
 *
 * <p>The vendored SMSLib code sends the PDU as ASCII hex text with the length
 * covering the whole PDU. Some Huawei firmware families (K3765 line) reject
 * that with {@code +CMS ERROR: 304} and require the TPDU (SMSC octet dropped)
 * with the correspondingly shorter length. Standard modems take the full form.
 */
public enum PduSubmission {
    /** Full PDU, ASCII hex text — vendored SMSLib behaviour (default). */
    FULL_HEX,
    /** TPDU only (zero-length SMSC octet dropped), ASCII hex text — Huawei K3765 family. */
    TPDU_HEX
}
