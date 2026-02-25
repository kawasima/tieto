package net.unit8.tieto.core.proxy;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Metadata about a single method parameter, including whether it is a
 * domain object (serialized as JSONB) or a simple type (bound directly).
 */
public record ParameterInfo(
        int index,
        String name,
        Class<?> type,
        boolean isDomainObject
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
        List<ParameterInfo> result = new ArrayList<>(params.length);
        for (int i = 0; i < params.length; i++) {
            Parameter p = params[i];
            Class<?> type = p.getType();
            boolean isDomain = !isSimpleType(type);
            String name = p.getName();
            result.add(new ParameterInfo(i, name, type, isDomain));
        }
        return result;
    }

    private static boolean isSimpleType(Class<?> type) {
        return SIMPLE_TYPES.contains(type) || type.isEnum();
    }
}
