package net.unit8.tieto.spring;

import net.unit8.tieto.core.TietoClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;

import javax.sql.DataSource;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Auto-configuration guards (#23) and the customizer hook (#24).
 */
class TietoAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TietoAutoConfiguration.class));

    @Test
    void noDataSource_backsOffWithoutFailing() {
        runner.run(ctx -> assertThat(ctx)
                .hasNotFailed()
                .doesNotHaveBean(TietoClient.class));
    }

    @Test
    void multipleDataSourcesWithoutPrimary_backsOff() {
        runner.withUserConfiguration(TwoDataSources.class).run(ctx -> assertThat(ctx)
                .hasNotFailed()
                .doesNotHaveBean(TietoClient.class));
    }

    @Test
    void singleDataSource_createsClient() {
        runner.withUserConfiguration(OneDataSource.class).run(ctx -> assertThat(ctx)
                .hasNotFailed()
                .hasSingleBean(TietoClient.class));
    }

    @Test
    void multipleDataSourcesWithPrimary_createsClient() {
        runner.withUserConfiguration(TwoDataSourcesWithPrimary.class).run(ctx -> assertThat(ctx)
                .hasNotFailed()
                .hasSingleBean(TietoClient.class));
    }

    @Test
    void customizerIsAppliedAndTransactionWrappingPreserved() {
        AtomicBoolean applied = new AtomicBoolean(false);
        runner.withUserConfiguration(OneDataSource.class)
                .withBean(TietoClientCustomizer.class, () -> builder -> applied.set(true))
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(TietoClient.class);
                    assertThat(applied).as("customizer applied").isTrue();
                    assertThat(ctx.getBean(TietoClient.class).dataSource())
                            .as("auto-configured client keeps the transaction-aware wrapping")
                            .isInstanceOf(TransactionAwareDataSourceProxy.class);
                });
    }

    @Test
    void userProvidedClientBacksOffAutoConfig() {
        TietoClient custom = TietoClient.builder(new NoOpDataSource()).build();
        runner.withUserConfiguration(OneDataSource.class)
                .withBean("customClient", TietoClient.class, () -> custom)
                .run(ctx -> assertThat(ctx.getBean(TietoClient.class)).isSameAs(custom));
    }

    @Configuration(proxyBeanMethods = false)
    static class OneDataSource {
        @Bean
        DataSource dataSource() {
            return new NoOpDataSource();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TwoDataSources {
        @Bean
        DataSource a() {
            return new NoOpDataSource();
        }

        @Bean
        DataSource b() {
            return new NoOpDataSource();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TwoDataSourcesWithPrimary {
        @Bean
        @Primary
        DataSource a() {
            return new NoOpDataSource();
        }

        @Bean
        DataSource b() {
            return new NoOpDataSource();
        }
    }
}
