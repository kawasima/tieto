package net.unit8.tieto.core.mapper;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import net.unit8.tieto.core.exception.MappingException;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Convention-based mapper using Jackson.
 *
 * <p>Maps domain objects to/from JSON using field names as JSON keys
 * (camelCase). Supports Java records, java.time types, and nested objects.</p>
 *
 * <p>Sealed interface hierarchies (used for composable Specification trees) are
 * serialized polymorphically: each node carries a {@code "kind"} discriminator
 * whose value is the camelCase simple name of the concrete type (e.g.
 * {@code And} &rarr; {@code "and"}, {@code ShippedAfter} &rarr;
 * {@code "shippedAfter"}). Other JSON keys are the record component names. This
 * is applied by convention &mdash; domain Specification types stay free of any
 * Jackson/tieto annotation. The format is stable so an AI-generated PostgreSQL
 * function can recursively interpret the tree.</p>
 */
public final class ConventionMapper {

    /**
     * Mix-in that injects the {@code "kind"} property-based type discriminator.
     * Applied to the root of a sealed hierarchy; concrete subtype names are
     * registered separately via {@link ObjectMapper#registerSubtypes(NamedType...)}.
     */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    private interface KindTypeInfo {}

    private final ObjectMapper objectMapper;
    private final ConcurrentMap<Class<?>, ObjectMapper> sealedMappers = new ConcurrentHashMap<>();

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
        ObjectMapper mapper = type.isSealed() ? sealedMapperFor(type) : objectMapper;
        return new DomainMapper<>() {
            @Override
            public String toJson(T obj) {
                try {
                    // writerFor(type) makes the declared (static) type the sealed
                    // root, so the root node also gets its "kind" discriminator.
                    return mapper.writerFor(type).writeValueAsString(obj);
                } catch (JsonProcessingException e) {
                    throw new MappingException(
                            "Failed to serialize " + type.getName(), e);
                }
            }

            @Override
            public T fromJson(String json, Class<T> t) {
                try {
                    return mapper.readValue(json, t);
                } catch (JsonProcessingException e) {
                    throw new MappingException(
                            "Failed to deserialize to " + t.getName(), e);
                }
            }
        };
    }

    /**
     * Returns (and caches) an ObjectMapper configured for the given sealed
     * hierarchy with {@code "kind"}-based polymorphic typing.
     */
    private ObjectMapper sealedMapperFor(Class<?> sealedRoot) {
        return sealedMappers.computeIfAbsent(sealedRoot, root -> {
            ObjectMapper copy = objectMapper.copy();
            configureSealed(copy, root);
            return copy;
        });
    }

    private static void configureSealed(ObjectMapper mapper, Class<?> sealedRoot) {
        mapper.addMixIn(sealedRoot, KindTypeInfo.class);
        Class<?>[] subtypes = sealedRoot.getPermittedSubclasses();
        if (subtypes == null) {
            return;
        }
        for (Class<?> sub : subtypes) {
            mapper.registerSubtypes(new NamedType(sub, kindOf(sub)));
            if (sub.isSealed()) {
                configureSealed(mapper, sub);
            }
        }
    }

    /**
     * Derives the {@code "kind"} discriminator from a simple class name:
     * the first character is lower-cased ({@code ShippedAfter} &rarr;
     * {@code "shippedAfter"}).
     */
    private static String kindOf(Class<?> type) {
        String simple = type.getSimpleName();
        if (simple.isEmpty()) {
            return simple;
        }
        return Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
    }
}
