package net.unit8.tieto.core.mapper;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link MapperRegistry} resolution order: an explicitly registered
 * {@link DomainMapper} wins, otherwise it falls back to the convention mapper.
 */
class MapperRegistryTest {

    record Sample(String name) {
    }

    static final class SampleMapper implements DomainMapper<Sample> {
        @Override
        public String toJson(Sample domainObject) {
            return "{\"name\":\"" + domainObject.name() + "\"}";
        }

        @Override
        public Sample fromJson(String json, Class<Sample> type) {
            return new Sample("from-explicit-mapper");
        }
    }

    @Test
    void resolvesAnExplicitlyRegisteredMapper() {
        SampleMapper mapper = new SampleMapper();
        MapperRegistry registry = MapperRegistry.builder()
                .register(Sample.class, mapper)
                .build();

        assertThat(registry.resolve(Sample.class)).isSameAs(mapper);
    }

    @Test
    void fallsBackToTheConventionMapperForAnUnregisteredType() {
        MapperRegistry registry = MapperRegistry.builder().build();

        DomainMapper<Sample> mapper = registry.resolve(Sample.class);

        assertThat(mapper).isNotNull();
        Sample original = new Sample("alice");
        String json = mapper.toJson(original);
        assertThat(json).contains("alice");
        assertThat(mapper.fromJson(json, Sample.class)).isEqualTo(original);
    }

    @Test
    void explicitRegistrationOnlyAffectsTheRegisteredType() {
        SampleMapper explicit = new SampleMapper();
        MapperRegistry registry = MapperRegistry.builder()
                .register(Sample.class, explicit)
                .build();

        assertThat(registry.resolve(Sample.class)).isSameAs(explicit);
        // A different, unregistered type still resolves via convention.
        assertThat(registry.resolve(String.class)).isNotNull();
    }
}
