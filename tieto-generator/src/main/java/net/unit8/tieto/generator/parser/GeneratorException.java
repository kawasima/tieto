package net.unit8.tieto.generator.parser;

/**
 * Runtime exception for generator-related errors.
 */
public class GeneratorException extends RuntimeException {

    public GeneratorException(String message) {
        super(message);
    }

    public GeneratorException(String message, Throwable cause) {
        super(message, cause);
    }
}
