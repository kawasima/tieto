package net.unit8.tieto.spring;

import net.unit8.tieto.core.TietoClient;
import net.unit8.tieto.spring.testrepos.PlainService;
import net.unit8.tieto.spring.testrepos.SampleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the property-driven registration path: setting {@code tieto.base-packages}
 * registers the {@link TietoRepository}-annotated interfaces via
 * {@link TietoAutoConfiguration}, with no {@code @EnableTietoRepositories} present.
 *
 * <p>Uses {@link ApplicationContextRunner} with {@link AutoConfigurations} so the
 * auto-configuration is loaded the way Spring Boot loads it — honouring
 * {@code @AutoConfiguration(after = ...)} ordering and the
 * {@code @ConditionalOnSingleCandidate(DataSource)} guard.</p>
 */
class TietoPropertyRegistrationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TietoAutoConfiguration.class))
            .withUserConfiguration(DataSourceConfig.class);

    @Test
    void registersRepositoriesFromTheBasePackagesProperty() {
        runner.withPropertyValues("tieto.base-packages=net.unit8.tieto.spring.testrepos")
                .run(ctx -> assertThat(ctx)
                        .hasSingleBean(SampleRepository.class)
                        .doesNotHaveBean(PlainService.class));
    }

    @Test
    void registersNothingWhenThePropertyIsAbsent() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(SampleRepository.class));
    }

    @Test
    void exposesTietoClientAndPropertiesAsBeans() {
        runner.run(ctx -> assertThat(ctx)
                .hasSingleBean(TietoProperties.class)
                .hasSingleBean(TietoClient.class));
    }

    @Test
    void registersOnceWhenBothTheAnnotationAndPropertyCoverTheSamePackage() {
        runner.withUserConfiguration(AnnotationDrivenConfig.class)
                .withPropertyValues("tieto.base-packages=net.unit8.tieto.spring.testrepos")
                .run(ctx -> {
                    // Same repository reached via both paths must be idempotent, not a collision error.
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBeanNamesForType(SampleRepository.class)).hasSize(1);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class DataSourceConfig {
        @Bean
        DataSource dataSource() {
            return new NoOpDataSource();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTietoRepositories("net.unit8.tieto.spring.testrepos")
    static class AnnotationDrivenConfig {
    }
}
