package com.hushsign.protocol;

import com.networknt.schema.ValidationMessage;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Raised when a payload does not conform to its JSON Schema v1.
 */
public class ProtocolValidationException extends RuntimeException {

    private final MessageType type;

    public ProtocolValidationException(MessageType type, Set<ValidationMessage> messages) {
        super(messages.stream()
                .map(ValidationMessage::getMessage)
                .distinct()
                .collect(Collectors.joining("; ")));
        this.type = type;
    }

    public MessageType type() {
        return type;
    }
}
