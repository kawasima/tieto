package net.unit8.tieto.core.proxy;

import net.unit8.tieto.core.exception.FunctionCallException;
import net.unit8.tieto.core.mapper.DomainMapper;
import net.unit8.tieto.core.mapper.MapperRegistry;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * Sealed interface for handling different Repository method return types.
 *
 * <p>Each variant knows how to extract results from a {@link ResultSet}
 * and convert them to the expected Java type.</p>
 */
public sealed interface ReturnTypeHandler {

    /**
     * Extracts the result from a {@link ResultSet} and converts it to the
     * expected Java type.
     */
    Object extractResult(ResultSet rs, MapperRegistry registry) throws SQLException;

    /**
     * Handles {@code List<T>} return types.
     */
    record ListHandler(Class<?> elementType) implements ReturnTypeHandler {
        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public Object extractResult(ResultSet rs, MapperRegistry registry)
                throws SQLException {
            List<Object> results = new ArrayList<>();
            DomainMapper mapper = registry.resolve(elementType);
            while (rs.next()) {
                String json = rs.getString(1);
                if (json != null) {
                    results.add(mapper.fromJson(json, elementType));
                }
            }
            return Collections.unmodifiableList(results);
        }
    }

    /**
     * Handles single object return types (e.g., {@code Order}).
     */
    record SingleHandler(Class<?> type) implements ReturnTypeHandler {
        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public Object extractResult(ResultSet rs, MapperRegistry registry)
                throws SQLException {
            if (!rs.next()) {
                throw new FunctionCallException(
                        "Expected single result but got none for type " + type.getName());
            }
            String json = rs.getString(1);
            if (json == null) {
                throw new FunctionCallException(
                        "Expected non-null result for type " + type.getName());
            }
            DomainMapper mapper = registry.resolve(type);
            return mapper.fromJson(json, type);
        }
    }

    /**
     * Handles {@code Optional<T>} return types.
     */
    record OptionalHandler(Class<?> elementType) implements ReturnTypeHandler {
        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public Object extractResult(ResultSet rs, MapperRegistry registry)
                throws SQLException {
            if (!rs.next()) {
                return Optional.empty();
            }
            String json = rs.getString(1);
            if (json == null) {
                return Optional.empty();
            }
            DomainMapper mapper = registry.resolve(elementType);
            return Optional.of(mapper.fromJson(json, elementType));
        }
    }

    /**
     * Handles {@code void} return types (updates, deletes).
     */
    record VoidHandler() implements ReturnTypeHandler {
        @Override
        public Object extractResult(ResultSet rs, MapperRegistry registry) {
            return null;
        }
    }

    /**
     * Analyzes a method's return type and returns the appropriate handler.
     */
    static ReturnTypeHandler from(Method method) {
        Type returnType = method.getGenericReturnType();

        if (returnType == void.class || returnType == Void.class) {
            return new VoidHandler();
        }

        if (returnType instanceof ParameterizedType pt) {
            Class<?> raw = (Class<?>) pt.getRawType();
            Type arg = pt.getActualTypeArguments()[0];

            if (List.class.isAssignableFrom(raw)) {
                return new ListHandler((Class<?>) arg);
            }
            if (Optional.class.isAssignableFrom(raw)) {
                return new OptionalHandler((Class<?>) arg);
            }
        }

        return new SingleHandler((Class<?>) returnType);
    }
}
