package net.unit8.tieto.core.proxy;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.*;
import java.util.ArrayList;
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
 */
public record ParameterInfo(
        int index,
        String name,
        Class<?> type,
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
            Class<?> type = p.getType();
            boolean optional = type == Optional.class;
            if (optional) {
                type = optionalElementType(genericTypes[i]);
            }
            boolean isDomain = !isSimpleType(type);
            result.add(new ParameterInfo(i, p.getName(), type, isDomain, optional));
        }
        return result;
    }

    /**
     * The {@code E} of an {@code Optional<E>} parameter. Falls back to
     * {@code Object} for a raw or wildcard {@code Optional<?>}.
     */
    private static Class<?> optionalElementType(Type genericType) {
        if (genericType instanceof ParameterizedType pt) {
            Type arg = pt.getActualTypeArguments()[0];
            if (arg instanceof Class<?> c) {
                return c;
            }
            if (arg instanceof ParameterizedType inner && inner.getRawType() instanceof Class<?> rawClass) {
                return rawClass;
            }
        }
        return Object.class;
    }

    private static boolean isSimpleType(Class<?> type) {
        return SIMPLE_TYPES.contains(type) || type.isEnum();
    }
}
