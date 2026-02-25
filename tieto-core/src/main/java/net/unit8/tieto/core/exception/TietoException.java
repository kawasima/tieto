package net.unit8.tieto.core.exception;

/**
 * Base runtime exception for all tieto errors.
 */
public class TietoException extends RuntimeException {

    public TietoException(String message) {
        super(message);
    }

    public TietoException(String message, Throwable cause) {
        super(message, cause);
    }
}
