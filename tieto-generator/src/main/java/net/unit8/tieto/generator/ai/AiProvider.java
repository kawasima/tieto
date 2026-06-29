package net.unit8.tieto.generator.ai;

/**
 * Interface for AI backends that generate PostgreSQL functions from prompts.
 */
public interface AiProvider {

    /**
     * Generates a PostgreSQL function from the given prompt.
     *
     * @param prompt the prompt containing method spec, schema, and rules
     * @return the generated function
     */
    GeneratedFunction generateFunction(String prompt);

    /**
     * A short, secret-free description of the backend and model/parameters used,
     * recorded in the generated SQL artifact header for auditability.
     */
    String provenance();
}
