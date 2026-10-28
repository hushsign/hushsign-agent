package com.hushsign.agent.runtime.audit;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** A log that records nothing, for a handler used without an audit chain (tests, tools). */
public final class NoopAuditLog implements AuditLog {

    public static final NoopAuditLog INSTANCE = new NoopAuditLog();

    private NoopAuditLog() {
    }

    @Override
    public Optional<AuditEntry> append(String type, String emissionId, String detail) {
        return Optional.empty();
    }

    @Override
    public ChainVerification verify() {
        return ChainVerification.ok(0, Instant.EPOCH);
    }

    @Override
    public List<AuditEntry> tail(int count) {
        return List.of();
    }

    @Override
    public String location() {
        return "none";
    }

    @Override
    public java.util.Optional<DigestWindow> window(java.time.Instant from, java.time.Instant to) {
        return java.util.Optional.empty();
    }

    @Override
    public boolean status() {
        return true;
    }
}
