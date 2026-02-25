package net.unit8.tieto.core.exception;

/**
 * Thrown when domain object serialization or deserialization fails.
 */
public class MappingException extends TietoException {

    public MappingException(String message, Throwable cause) {
        super(message, cause);
    }
}
