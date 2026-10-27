package com.hushsign.agent.runtime.sensitive;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.encoder.EncoderBase;
import ch.qos.logback.core.encoder.LayoutWrappingEncoder;
import ch.qos.logback.classic.PatternLayout;

import java.nio.charset.StandardCharsets;

/**
 * A logback encoder for the logs that leave the device: it formats the event
 * exactly like the normal pattern encoder and then scrubs the result through
 * {@link SensitiveRedactor}.
 *
 * <p>Wired into {@code agent-clean.log} and the console, which are the two sinks
 * that get copied elsewhere — the journal by whoever collects logs, and the file
 * by whoever asks for a support bundle. {@code agent.log} deliberately uses the
 * plain encoder: it is the operator's own copy, it stays on the device, and it
 * is what someone reads when they need to know which target hit a cap.
 */
public class RedactingEncoder extends EncoderBase<ILoggingEvent> {

    private final LayoutWrappingEncoder<ILoggingEvent> delegate = new LayoutWrappingEncoder<>();

    private final PatternLayout layout = new PatternLayout();

    private String pattern;

    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    public String getPattern() {
        return this.pattern;
    }

    @Override
    public void start() {
        if (this.pattern != null) {
            this.layout.setPattern(this.pattern);
        }
        this.layout.setContext(getContext());
        this.layout.start();
        this.delegate.setContext(getContext());
        this.delegate.setLayout(this.layout);
        this.delegate.start();
        super.start();
    }

    @Override
    public void stop() {
        this.delegate.stop();
        this.layout.stop();
        super.stop();
    }

    @Override
    public byte[] headerBytes() {
        return this.delegate.headerBytes();
    }

    @Override
    public byte[] footerBytes() {
        return this.delegate.footerBytes();
    }

    @Override
    public byte[] encode(ILoggingEvent event) {
        byte[] encoded = this.delegate.encode(event);
        if (encoded == null || encoded.length == 0) {
            return encoded;
        }
        String text = new String(encoded, StandardCharsets.UTF_8);
        String clean = SensitiveRedactor.scrub(text);
        return clean.equals(text) ? encoded : clean.getBytes(StandardCharsets.UTF_8);
    }
}
