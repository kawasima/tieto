package net.unit8.tieto.core.mapper;

/**
 * Interface for custom domain object serialization/deserialization.
 *
 * <p>Register explicit mappers via {@code TietoClientBuilder.mapper()} for
 * domain classes that require special handling beyond the convention-based
 * Jackson mapping.</p>
 *
 * @param <T> the domain object type
 */
public interface DomainMapper<T> {

    /**
     * Serializes a domain object to a JSON string for use as a JSONB parameter.
     *
     * @param domainObject the domain object to serialize
     * @return JSON string representation
     */
    String toJson(T domainObject);

    /**
     * Deserializes a JSON string from a JSONB result to a domain object.
     *
     * @param json the JSON string
     * @param type the target class
     * @return the deserialized domain object
     */
    T fromJson(String json, Class<T> type);
}
