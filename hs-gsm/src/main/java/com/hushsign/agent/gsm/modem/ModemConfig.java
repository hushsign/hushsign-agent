package com.hushsign.agent.gsm.modem;

/**
 * Per-modem configuration (P1-S2). Field-proven defaults from the internal
 * fork (P0-S7): 8N1, 10 s open timeout, blocking reads.
 */
public record ModemConfig(
        String gatewayId,
        String simPin,
        String simPin2,
        int baudRate,
        int openTimeoutMs,
        int probeAttempts,
        int probeRetryDelayMs,
        int responseTimeoutMs,
        int commandWaitMs,
        int pollWaitMs,
        int maxConsecutiveErrors,
        boolean initializeOnOpen,
        PduSubmission pduSubmission) {

    public static Builder builder(String gatewayId) {
        return new Builder(gatewayId);
    }

    /** Copy with the full modem init (SIM/PIN, registration, PDU mode) skipped — used by port scanning. */
    public ModemConfig probeOnly() {
        return new ModemConfig(gatewayId, simPin, simPin2, baudRate, openTimeoutMs, probeAttempts,
                probeRetryDelayMs, responseTimeoutMs, commandWaitMs, pollWaitMs,
                maxConsecutiveErrors, false, pduSubmission);
    }

    public static final class Builder {
        private final String gatewayId;
        private String simPin;
        private String simPin2;
        private int baudRate = 115200;
        private int openTimeoutMs = 10_000;
        private int probeAttempts = 3;
        private int probeRetryDelayMs = 500;
        private int responseTimeoutMs = 30_000;
        private int commandWaitMs = 700;
        private int pollWaitMs = 200;
        private int maxConsecutiveErrors = 3;
        private boolean initializeOnOpen = true;
        private PduSubmission pduSubmission = PduSubmission.FULL_HEX;

        private Builder(String gatewayId) {
            this.gatewayId = gatewayId;
        }

        public Builder simPin(String simPin) {
            this.simPin = simPin;
            return this;
        }

        public Builder simPin2(String simPin2) {
            this.simPin2 = simPin2;
            return this;
        }

        public Builder baudRate(int baudRate) {
            this.baudRate = baudRate;
            return this;
        }

        public Builder openTimeoutMs(int openTimeoutMs) {
            this.openTimeoutMs = openTimeoutMs;
            return this;
        }

        public Builder probeAttempts(int probeAttempts) {
            this.probeAttempts = probeAttempts;
            return this;
        }

        public Builder probeRetryDelayMs(int probeRetryDelayMs) {
            this.probeRetryDelayMs = probeRetryDelayMs;
            return this;
        }

        public Builder responseTimeoutMs(int responseTimeoutMs) {
            this.responseTimeoutMs = responseTimeoutMs;
            return this;
        }

        public Builder commandWaitMs(int commandWaitMs) {
            this.commandWaitMs = commandWaitMs;
            return this;
        }

        public Builder pollWaitMs(int pollWaitMs) {
            this.pollWaitMs = pollWaitMs;
            return this;
        }

        public Builder maxConsecutiveErrors(int maxConsecutiveErrors) {
            this.maxConsecutiveErrors = maxConsecutiveErrors;
            return this;
        }

        /**
         * Runs the full modem init (SIM/PIN check, network registration,
         * PDU mode switch) right after the probe. Defaults to {@code true} —
         * a modem that cannot read its SIM must never look READY. Port
         * scanning uses {@link ModemConfig#probeOnly()} instead.
         */
        public Builder initializeOnOpen(boolean initializeOnOpen) {
            this.initializeOnOpen = initializeOnOpen;
            return this;
        }

        /**
         * PDU wire format for {@code AT+CMGS} (P1-S14): {@link PduSubmission#TPDU_HEX}
         * drops the zero-length SMSC octet and shortens the length — required
         * by the Huawei K3765 firmware family, which rejects the full form
         * with {@code +CMS ERROR: 304}.
         */
        public Builder pduSubmission(PduSubmission pduSubmission) {
            this.pduSubmission = pduSubmission;
            return this;
        }

        public ModemConfig build() {
            if (baudRate <= 0 || openTimeoutMs <= 0 || responseTimeoutMs <= 0 || pollWaitMs <= 0) {
                throw new IllegalArgumentException("baud/open/response/poll timings must be positive");
            }
            if (probeAttempts < 1) {
                throw new IllegalArgumentException("probeAttempts must be >= 1");
            }
            if (commandWaitMs < 0 || probeRetryDelayMs < 0) {
                throw new IllegalArgumentException("delays must not be negative");
            }
            if (maxConsecutiveErrors < 1) {
                throw new IllegalArgumentException("maxConsecutiveErrors must be >= 1");
            }
            return new ModemConfig(gatewayId, simPin, simPin2, baudRate, openTimeoutMs,
                    probeAttempts, probeRetryDelayMs, responseTimeoutMs, commandWaitMs,
                    pollWaitMs, maxConsecutiveErrors, initializeOnOpen, pduSubmission);
        }
    }
}
