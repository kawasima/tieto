package net.unit8.tieto.core.proxy;

import net.unit8.tieto.core.exception.FunctionCallException;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Metadata about a single method parameter, including whether it is a
 * domain object (serialized as JSONB) or a simple type (bound directly).
 *
 * <p>For an {@code Optional<E>} parameter, {@link #type()} is the element type
 * {@code E} and {@link #isOptional()} is {@code true}; the invoker unwraps the
 * Optional (empty &rarr; SQL NULL, present &rarr; the value) and binds it as
 * {@code E}.</p>
 *
 * <p>For a collection-shaped parameter ({@code List<E>}, or {@code Optional<List<E>>}),
 * {@link #type()} is the raw container ({@code List}) and {@link #elementType()} is the
 * generic element {@code E}. The element type is carried so that a sealed (e.g.
 * Specification) element serializes with its {@code "kind"} discriminator &mdash; the
 * container type alone reaches no sealed hierarchy. It is {@code null} for non-collection
 * parameters.</p>
 */
public record ParameterInfo(
        int index,
        String name,
        Class<?> type,
        Class<?> elementType,
        boolean isDomainObject,
        boolean isOptional
) {

    private static final Set<Class<?>> SIMPLE_TYPES = Set.of(
            // Primitives and wrappers
            boolean.class, Boolean.class,
            byte.class, Byte.class,
            short.class, Short.class,
            int.class, Integer.class,
            long.class, Long.class,
            float.class, Float.class,
            double.class, Double.class,
            char.class, Character.class,
            // Common types
            String.class,
            BigDecimal.class,
            BigInteger.class,
            UUID.class,
            // Date/Time
            LocalDate.class,
            LocalTime.class,
            LocalDateTime.class,
            OffsetDateTime.class,
            ZonedDateTime.class,
            Instant.class
    );

    /**
     * Analyzes all parameters of a method and returns their metadata.
     */
    public static List<ParameterInfo> from(Method method) {
        Parameter[] params = method.getParameters();
        Type[] genericTypes = method.getGenericParameterTypes();
        List<ParameterInfo> result = new ArrayList<>(params.length);
        for (int i = 0; i < params.length; i++) {
            Parameter p = params[i];
            boolean optional = p.getType() == Optional.class;
            // For Optional<E>, bind as E; the bound generic type is E itself
            // (which may be a concrete class or a collection such as List<X>).
            Type bound = optional ? optionalElementGeneric(genericTypes[i]) : genericTypes[i];
            Class<?> type = rawClassOf(bound);
            Class<?> elementType = collectionElementType(bound);
            boolean isDomain = !isSimpleType(type);
            result.add(new ParameterInfo(i, p.getName(), type, elementType, isDomain, optional));
        }
        return result;
    }

    /**
     * The {@code E} of an {@code Optional<E>}, returned as its generic {@link Type}
     * (so an {@code Optional<List<X>>} keeps its {@code List<X>} element type). Rejects a
     * raw {@code Optional}, a wildcard, or a type variable with a clear error — the same
     * way {@link ReturnTypeHandler} treats an unresolvable return type — rather than
     * degrading to {@code Object} and mis-binding the value as JSONB.
     */
    private static Type optionalElementGeneric(Type genericType) {
        if (genericType instanceof ParameterizedType pt) {
            Type arg = pt.getActualTypeArguments()[0];
            if (arg instanceof Class<?> || arg instanceof ParameterizedType inner
                    && inner.getRawType() instanceof Class<?>) {
                return arg;
            }
        }
        throw new FunctionCallException(
                "Unsupported parameter type " + genericType.getTypeName()
                        + ": an Optional parameter must have a concrete element type"
                        + " (no raw Optional, wildcard, or type variable).");
    }

    /** The raw {@link Class} backing a {@link Type} (the container for a parameterized type). */
    private static Class<?> rawClassOf(Type type) {
        if (type instanceof Class<?> c) {
            return c;
        }
        if (type instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> raw) {
            return raw;
        }
        throw new FunctionCallException(
                "Unsupported parameter type " + type.getTypeName());
    }

    /**
     * The element {@code E} of a {@code Collection<E>}-typed parameter, or {@code null}
     * when the parameter is not a collection or its element is not a concrete class. Used
     * to register the discriminator for sealed element types serialized inside a JSONB array.
     *
     * <p>Scope: top-level {@code Collection} (e.g. {@code List}/{@code Set}) element types
     * only — the shapes #47 targets. A sealed type reached only through a non-collection
     * generic container ({@code Map<K, Spec>}, a wildcard {@code List<? extends Spec>}, or a
     * nested generic element {@code List<Optional<Spec>>}) is not registered here and would
     * serialize without its {@code "kind"} tag; those shapes are not part of the documented
     * Repository-parameter contract. A direct sealed parameter or {@code Optional<Spec>} is
     * handled by the normal (non-collection) path via {@link #rawClassOf(Type)}.</p>
     */
    private static Class<?> collectionElementType(Type type) {
        if (type instanceof ParameterizedType pt
                && pt.getRawType() instanceof Class<?> raw
                && Collection.class.isAssignableFrom(raw)) {
            Type arg = pt.getActualTypeArguments()[0];
            if (arg instanceof Class<?> c) {
                return c;
            }
            if (arg instanceof ParameterizedType inner && inner.getRawType() instanceof Class<?> innerRaw) {
                return innerRaw;
            }
        }
        return null;
    }

    private static boolean isSimpleType(Class<?> type) {
        return SIMPLE_TYPES.contains(type) || type.isEnum();
    }
}
