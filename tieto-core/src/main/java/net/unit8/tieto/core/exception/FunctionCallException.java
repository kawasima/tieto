package net.unit8.tieto.core.exception;

import java.sql.SQLException;

/**
 * Thrown when a PostgreSQL function call fails.
 *
 * <p>Wraps the original {@link SQLException} and exposes the PostgreSQL
 * SQLSTATE code for programmatic error handling.</p>
 */
public class FunctionCallException extends TietoException {

    private final String sqlState;
    private final String pgMessage;

    public FunctionCallException(String message, SQLException cause) {
        super(message, cause);
        this.sqlState = cause.getSQLState();
        this.pgMessage = cause.getMessage();
    }

    public FunctionCallException(String message) {
        super(message);
        this.sqlState = null;
        this.pgMessage = null;
    }

    /**
     * Returns the PostgreSQL SQLSTATE code, or null if not available.
     */
    public String getSqlState() {
        return sqlState;
    }

    /**
     * Returns the PostgreSQL error message, or null if not available.
     */
    public String getPgMessage() {
        return pgMessage;
    }
}
