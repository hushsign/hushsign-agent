package com.hushsign.protocol;

import com.networknt.schema.Error;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Raised when a payload does not conform to its JSON Schema v1.
 */
public class ProtocolValidationException extends RuntimeException {

    private final MessageType type;

    public ProtocolValidationException(MessageType type, List<Error> errors) {
        super(errors.stream()
                .map(Error::getMessage)
                .distinct()
                .collect(Collectors.joining("; ")));
        this.type = type;
    }

    public MessageType type() {
        return type;
    }
}
