package net.unit8.tieto.core.mapper;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.JavaType;
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
import java.util.Collection;
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

    /** Cache key for a container type carrying a sealed-bearing element type. */
    private record ContainerKey(Class<?> type, Class<?> elementType) {}

    private final ObjectMapper objectMapper;
    private final ConcurrentMap<Class<?>, ObjectMapper> configuredMappers = new ConcurrentHashMap<>();
    private final ConcurrentMap<Class<?>, DomainMapper<?>> domainMappers = new ConcurrentHashMap<>();
    private final ConcurrentMap<ContainerKey, DomainMapper<?>> containerMappers = new ConcurrentHashMap<>();

    public ConventionMapper() {
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .registerModule(new Jdk8Module())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    }

    /**
     * Returns (and caches) a {@link DomainMapper} for the given type using
     * convention-based mapping. The mapper is immutable per type, so it is
     * built once rather than re-allocated on every resolve.
     */
    @SuppressWarnings("unchecked")
    public <T> DomainMapper<T> forType(Class<T> type) {
        return (DomainMapper<T>) domainMappers.computeIfAbsent(type, this::buildDomainMapper);
    }

    /**
     * Returns (and caches) a {@link DomainMapper} for a collection-shaped value whose
     * declared element is {@code elementType}. When {@code elementType} is {@code null}
     * this is equivalent to {@link #forType(Class)}. Otherwise the discriminator is
     * registered for any sealed hierarchy reachable from the element type — not just the
     * container — and the element is serialized under its declared type so a sealed
     * Specification element keeps its {@code "kind"} tag inside a JSONB array.
     */
    @SuppressWarnings("unchecked")
    public <T> DomainMapper<T> forType(Class<T> type, Class<?> elementType) {
        if (elementType == null) {
            return forType(type);
        }
        return (DomainMapper<T>) containerMappers.computeIfAbsent(
                new ContainerKey(type, elementType),
                key -> buildDomainMapper(key.type(), key.elementType()));
    }

    private DomainMapper<Object> buildDomainMapper(Class<?> type) {
        return buildDomainMapper(type, null);
    }

    private DomainMapper<Object> buildDomainMapper(Class<?> type, Class<?> elementType) {
        ObjectMapper mapper = mapperFor(type, elementType);
        // For a collection element type, make the declared element type the sealed root so
        // each element carries its "kind"; otherwise writerFor(type) does the same for the root.
        JavaType writeType = (elementType != null && Collection.class.isAssignableFrom(type))
                ? mapper.getTypeFactory().constructCollectionType(asCollection(type), elementType)
                : mapper.getTypeFactory().constructType(type);
        return new DomainMapper<>() {
            @Override
            public String toJson(Object obj) {
                try {
                    return mapper.writerFor(writeType).writeValueAsString(obj);
                } catch (JsonProcessingException e) {
                    throw new MappingException(
                            "Failed to serialize " + type.getName(), e);
                }
            }

            @Override
            public Object fromJson(String json, Class<Object> t) {
                try {
                    return mapper.readValue(json, t);
                } catch (JsonProcessingException e) {
                    throw new MappingException(
                            "Failed to deserialize to " + t.getName(), e);
                }
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Collection<?>> asCollection(Class<?> type) {
        return (Class<? extends Collection<?>>) type;
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
        return configuredMappers.computeIfAbsent(type, t -> configuredMapper(collectSealedTypes(t)));
    }

    /**
     * As {@link #mapperFor(Class)}, additionally registering any sealed hierarchy reachable
     * from {@code elementType}. A container such as {@code List} reaches no sealed type on its
     * own, so the element type must be inspected for the discriminator to be applied.
     */
    private ObjectMapper mapperFor(Class<?> type, Class<?> elementType) {
        if (elementType == null) {
            return mapperFor(type);
        }
        Set<Class<?>> sealedTypes = collectSealedTypes(type);
        sealedTypes.addAll(collectSealedTypes(elementType));
        return configuredMapper(sealedTypes);
    }

    /**
     * The plain mapper when {@code sealedTypes} is empty, otherwise a copy with the
     * {@code "kind"} mix-in registered for each. Shared by both {@code mapperFor} overloads so
     * the registration logic lives in one place.
     */
    private ObjectMapper configuredMapper(Set<Class<?>> sealedTypes) {
        if (sealedTypes.isEmpty()) {
            return objectMapper;
        }
        ObjectMapper copy = objectMapper.copy();
        for (Class<?> sealed : sealedTypes) {
            registerKind(copy, sealed);
        }
        return copy;
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
