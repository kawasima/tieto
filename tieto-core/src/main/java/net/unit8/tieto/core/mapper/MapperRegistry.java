package net.unit8.tieto.core.mapper;

import java.util.HashMap;
import java.util.Map;

/**
 * Registry that resolves a {@link DomainMapper} for a given domain class.
 *
 * <p>Resolution order:</p>
 * <ol>
 *   <li>Explicit mapper registered via {@link Builder#register}</li>
 *   <li>Convention-based Jackson mapper (fallback)</li>
 * </ol>
 */
public final class MapperRegistry {

    private final Map<Class<?>, DomainMapper<?>> explicitMappers;
    private final ConventionMapper conventionMapper;

    private MapperRegistry(Map<Class<?>, DomainMapper<?>> explicitMappers) {
        this.explicitMappers = Map.copyOf(explicitMappers);
        this.conventionMapper = new ConventionMapper();
    }

    /**
     * Resolves a mapper for the given type.
     */
    @SuppressWarnings("unchecked")
    public <T> DomainMapper<T> resolve(Class<T> type) {
        DomainMapper<?> explicit = explicitMappers.get(type);
        if (explicit != null) {
            return (DomainMapper<T>) explicit;
        }
        return conventionMapper.forType(type);
    }

    /**
     * Resolves a mapper for a collection-shaped value whose declared element is
     * {@code elementType}, so a sealed element keeps its {@code "kind"} discriminator
     * inside a JSONB array. A {@code null} {@code elementType} (non-collection parameter)
     * behaves exactly like {@link #resolve(Class)}. An explicit mapper registered for the
     * container type still takes precedence.
     */
    @SuppressWarnings("unchecked")
    public <T> DomainMapper<T> resolve(Class<T> type, Class<?> elementType) {
        DomainMapper<?> explicit = explicitMappers.get(type);
        if (explicit != null) {
            return (DomainMapper<T>) explicit;
        }
        return conventionMapper.forType(type, elementType);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private final Map<Class<?>, DomainMapper<?>> mappers = new HashMap<>();

        Builder() {}

        /**
         * Registers an explicit mapper for a domain class.
         */
        public <T> Builder register(Class<T> domainClass, DomainMapper<T> mapper) {
            mappers.put(domainClass, mapper);
            return this;
        }

        public MapperRegistry build() {
            return new MapperRegistry(mappers);
        }
    }
}
