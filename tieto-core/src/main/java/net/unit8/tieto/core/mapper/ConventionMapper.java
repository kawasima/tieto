package net.unit8.tieto.core.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import net.unit8.tieto.core.exception.MappingException;

/**
 * Convention-based mapper using Jackson.
 *
 * <p>Maps domain objects to/from JSON using field names as JSON keys
 * (camelCase). Supports Java records, java.time types, and nested objects.</p>
 */
public final class ConventionMapper {

    private final ObjectMapper objectMapper;

    public ConventionMapper() {
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    }

    /**
     * Creates a {@link DomainMapper} for the given type using convention-based mapping.
     */
    public <T> DomainMapper<T> forType(Class<T> type) {
        return new DomainMapper<>() {
            @Override
            public String toJson(T obj) {
                try {
                    return objectMapper.writeValueAsString(obj);
                } catch (JsonProcessingException e) {
                    throw new MappingException(
                            "Failed to serialize " + type.getName(), e);
                }
            }

            @Override
            public T fromJson(String json, Class<T> t) {
                try {
                    return objectMapper.readValue(json, t);
                } catch (JsonProcessingException e) {
                    throw new MappingException(
                            "Failed to deserialize to " + t.getName(), e);
                }
            }
        };
    }
}
