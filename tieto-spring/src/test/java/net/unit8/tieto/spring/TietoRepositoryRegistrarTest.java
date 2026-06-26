package net.unit8.tieto.spring;

import net.unit8.tieto.core.TietoClient;
import net.unit8.tieto.spring.testrepos.PlainService;
import net.unit8.tieto.spring.testrepos.SampleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives {@link TietoRepositoryRegistrar}: only interfaces marked with
 * {@link TietoRepository} in the scanned packages become beans; plain interfaces
 * are left alone.
 */
class TietoRepositoryRegistrarTest {

    @Configuration
    @EnableTietoRepositories(basePackages = "net.unit8.tieto.spring.testrepos")
    static class ScanConfig {
        @Bean
        TietoClient tietoClient() {
            return TietoClient.builder(new NoOpDataSource()).build();
        }
    }

    @Test
    void registersOnlyAnnotatedRepositories() {
        try (var ctx = new AnnotationConfigApplicationContext(ScanConfig.class)) {
            assertThat(ctx.getBean(SampleRepository.class)).isNotNull();
            assertThat(ctx.getBeanNamesForType(PlainService.class)).isEmpty();
        }
    }

    @Test
    void defaultsToTheAnnotatedClassPackageWhenNoneGiven() {
        try (var ctx = new AnnotationConfigApplicationContext(DefaultPackageConfig.class)) {
            // No packages given: the annotated class's package (net.unit8.tieto.spring)
            // is scanned recursively, reaching SampleRepository in the testrepos sub-package.
            assertThat(ctx.getBean(SampleRepository.class)).isNotNull();
            assertThat(ctx.getBeanNamesForType(PlainService.class)).isEmpty();
        }
    }

    @Configuration
    @EnableTietoRepositories
    static class DefaultPackageConfig {
        @Bean
        TietoClient tietoClient() {
            return TietoClient.builder(new NoOpDataSource()).build();
        }
    }

    @Test
    void acceptsPackagesViaTheValueAlias() {
        try (var ctx = new AnnotationConfigApplicationContext(ValueAliasConfig.class)) {
            assertThat(ctx.getBean(SampleRepository.class)).isNotNull();
        }
    }

    @Configuration
    @EnableTietoRepositories("net.unit8.tieto.spring.testrepos")
    static class ValueAliasConfig {
        @Bean
        TietoClient tietoClient() {
            return TietoClient.builder(new NoOpDataSource()).build();
        }
    }

    @Test
    void acceptsPackagesViaBasePackageClasses() {
        try (var ctx = new AnnotationConfigApplicationContext(BasePackageClassesConfig.class)) {
            assertThat(ctx.getBean(SampleRepository.class)).isNotNull();
        }
    }

    @Configuration
    @EnableTietoRepositories(basePackageClasses = SampleRepository.class)
    static class BasePackageClassesConfig {
        @Bean
        TietoClient tietoClient() {
            return TietoClient.builder(new NoOpDataSource()).build();
        }
    }

    @Test
    void namesBeansWithSpringsJavaBeansDecapitalization() {
        try (var ctx = new AnnotationConfigApplicationContext(ScanConfig.class)) {
            // Acronym prefix is preserved (URLRepository), not lowercased to uRLRepository.
            assertThat(ctx.containsBean("URLRepository")).isTrue();
            assertThat(ctx.containsBean("sampleRepository")).isTrue();
        }
    }

    @Test
    void failsLoudlyWhenTwoRepositoriesShareABeanName() {
        assertThatThrownBy(() -> new AnnotationConfigApplicationContext(CollisionConfig.class).close())
                .hasStackTraceContaining("dupeRepository")
                .hasStackTraceContaining("already taken");
    }

    @Configuration
    @EnableTietoRepositories("net.unit8.tieto.dup")
    static class CollisionConfig {
        @Bean
        TietoClient tietoClient() {
            return TietoClient.builder(new NoOpDataSource()).build();
        }
    }
}
