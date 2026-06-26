package net.unit8.tieto.spring;

import net.unit8.tieto.core.TietoClient;
import net.unit8.tieto.spring.testrepos.PlainService;
import net.unit8.tieto.spring.testrepos.SampleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;

import javax.sql.DataSource;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the property-driven registration path: setting {@code tieto.base-packages}
 * registers the {@link TietoRepository}-annotated interfaces via
 * {@link TietoAutoConfiguration}, with no {@code @EnableTietoRepositories} present.
 */
class TietoPropertyRegistrationTest {

    @Test
    void registersRepositoriesFromTheBasePackagesProperty() {
        try (var ctx = new AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "test", Map.of("tieto.base-packages", "net.unit8.tieto.spring.testrepos")));
            ctx.register(PropertyConfig.class);
            ctx.refresh();

            assertThat(ctx.getBean(SampleRepository.class)).isNotNull();
            assertThat(ctx.getBeanNamesForType(PlainService.class)).isEmpty();
        }
    }

    @Test
    void registersNothingWhenThePropertyIsAbsent() {
        try (var ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(PropertyConfig.class);
            ctx.refresh();

            assertThat(ctx.getBeanNamesForType(SampleRepository.class)).isEmpty();
        }
    }

    @Test
    void exposesTietoClientAndPropertiesAsBeans() {
        try (var ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(PropertyConfig.class);
            ctx.refresh();

            // @EnableConfigurationProperties keeps TietoProperties injectable; the
            // auto-configuration contributes the TietoClient bean.
            assertThat(ctx.getBean(TietoProperties.class)).isNotNull();
            assertThat(ctx.getBean(TietoClient.class)).isNotNull();
        }
    }

    @Test
    void registersOnceWhenBothTheAnnotationAndPropertyCoverTheSamePackage() {
        try (var ctx = new AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "test", Map.of("tieto.base-packages", "net.unit8.tieto.spring.testrepos")));
            ctx.register(BothPathsConfig.class);
            ctx.refresh();

            // Same repository reached via both paths must be idempotent, not a collision error.
            assertThat(ctx.getBeanNamesForType(SampleRepository.class)).hasSize(1);
        }
    }

    @Configuration
    @Import(TietoAutoConfiguration.class)
    static class PropertyConfig {
        @Bean
        DataSource dataSource() {
            return new NoOpDataSource();
        }
    }

    @Configuration
    @Import(TietoAutoConfiguration.class)
    @EnableTietoRepositories("net.unit8.tieto.spring.testrepos")
    static class BothPathsConfig {
        @Bean
        DataSource dataSource() {
            return new NoOpDataSource();
        }
    }
}
