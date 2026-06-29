package net.unit8.tieto.core.mapper;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import net.unit8.tieto.core.exception.MappingException;

import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
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
 *
 * <p>The discriminator is registered for every sealed hierarchy reachable from
 * the mapped type, not just a top-level one: a sealed field nested inside a
 * non-sealed container (e.g. a criteria record holding a {@code Specification})
 * is handled too.</p>
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
    private final ConcurrentMap<Class<?>, ObjectMapper> configuredMappers = new ConcurrentHashMap<>();

    public ConventionMapper() {
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .registerModule(new Jdk8Module())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    }

    /**
     * Creates a {@link DomainMapper} for the given type using convention-based mapping.
     */
    public <T> DomainMapper<T> forType(Class<T> type) {
        ObjectMapper mapper = mapperFor(type);
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
     * Returns (and caches) an ObjectMapper for the given type. If any sealed
     * hierarchy is reachable from it — whether the type itself is sealed or it
     * merely holds a sealed field, directly or transitively — the mapper is
     * configured with {@code "kind"}-based polymorphic typing for every such
     * hierarchy. Otherwise the plain mapper is used.
     */
    private ObjectMapper mapperFor(Class<?> type) {
        // Cache per type, including the no-sealed-types case, so the reflective
        // graph walk runs at most once per type rather than on every bind/return.
        return configuredMappers.computeIfAbsent(type, t -> {
            Set<Class<?>> sealedTypes = collectSealedTypes(t);
            if (sealedTypes.isEmpty()) {
                return objectMapper;
            }
            ObjectMapper copy = objectMapper.copy();
            for (Class<?> sealed : sealedTypes) {
                registerKind(copy, sealed);
            }
            return copy;
        });
    }

    /** Registers the {@code "kind"} mix-in for one sealed type and its direct subtypes. */
    private static void registerKind(ObjectMapper mapper, Class<?> sealedType) {
        mapper.addMixIn(sealedType, KindTypeInfo.class);
        Class<?>[] subtypes = sealedType.getPermittedSubclasses();
        if (subtypes == null) {
            return;
        }
        for (Class<?> sub : subtypes) {
            mapper.registerSubtypes(new NamedType(sub, kindOf(sub)));
        }
    }

    /**
     * Collects every sealed type reachable from {@code root} by walking permitted
     * subclasses and record-component / field types (descending through generic
     * type arguments such as {@code List<Spec>}). JDK types are not descended
     * into. A visited set guards against the cycles that composable hierarchies
     * naturally contain (e.g. {@code And} holding {@code List<OrderSpec>}).
     */
    private static Set<Class<?>> collectSealedTypes(Class<?> root) {
        Set<Class<?>> sealed = new LinkedHashSet<>();
        collectSealedTypes(root, sealed, new HashSet<>());
        return sealed;
    }

    private static void collectSealedTypes(Class<?> type, Set<Class<?>> sealed, Set<Class<?>> visited) {
        if (type == null) {
            return;
        }
        if (type.isArray()) {
            collectSealedTypes(type.getComponentType(), sealed, visited);
            return;
        }
        if (isSkippable(type) || !visited.add(type)) {
            return;
        }
        if (type.isSealed()) {
            sealed.add(type);
            Class<?>[] subtypes = type.getPermittedSubclasses();
            if (subtypes != null) {
                for (Class<?> sub : subtypes) {
                    collectSealedTypes(sub, sealed, visited);
                }
            }
        }
        if (type.isRecord()) {
            for (RecordComponent rc : type.getRecordComponents()) {
                collectSealedTypes(rc.getGenericType(), sealed, visited);
            }
        } else {
            // Walk the whole class chain: an inherited field can reach a sealed type too.
            for (Class<?> c = type; c != null && !isSkippable(c); c = c.getSuperclass()) {
                for (Field field : c.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers())) {
                        collectSealedTypes(field.getGenericType(), sealed, visited);
                    }
                }
            }
        }
    }

    private static void collectSealedTypes(Type type, Set<Class<?>> sealed, Set<Class<?>> visited) {
        if (type instanceof Class<?> c) {
            collectSealedTypes(c, sealed, visited);
        } else if (type instanceof ParameterizedType pt) {
            collectSealedTypes(pt.getRawType(), sealed, visited);
            for (Type arg : pt.getActualTypeArguments()) {
                collectSealedTypes(arg, sealed, visited);
            }
        } else if (type instanceof GenericArrayType ga) {
            collectSealedTypes(ga.getGenericComponentType(), sealed, visited);
        }
        // Wildcards and type variables carry no concrete sealed class, so they are ignored.
    }

    /** JDK and primitive types never declare tieto Specification hierarchies; do not descend into them. */
    private static boolean isSkippable(Class<?> type) {
        if (type.isPrimitive() || type.isEnum()) {
            return true;
        }
        Package pkg = type.getPackage();
        return pkg != null && pkg.getName().startsWith("java.");
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
