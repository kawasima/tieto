package net.unit8.tieto.spring;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The behavioural guard warns when tieto is wrapped in a TransactionAwareDataSourceProxy but no
 * transaction manager binds to the DataSource tieto uses (so {@code @Transactional} participation
 * is silently lost — the Spring Data JPA case), and stays silent when a manager does bind to it.
 */
class TietoTransactionParticipationGuardTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TietoAutoConfiguration.class))
            .withBean("dataSource", DataSource.class, NoOpDataSource::new);

    @Test
    void silentWhenADataSourceTransactionManagerBindsToTheSameDataSource() {
        ListAppender<ILoggingEvent> log = capture();
        runner.withUserConfiguration(DataSourceTxManagerConfig.class)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(warnings(log)).isEmpty();
                });
    }

    @Test
    void warnsWhenAJpaStyleManagerExposesNoDataSource() {
        // Models Boot's auto-configured JpaTransactionManager: getDataSource() is null, so it
        // never binds a ConnectionHolder to the DataSource tieto's proxy delegates to.
        ListAppender<ILoggingEvent> log = capture();
        runner.withBean(PlatformTransactionManager.class, () -> new JpaStyleTransactionManager(null))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(warnings(log)).anySatisfy(message -> assertThat(message)
                            .contains("@Transactional")
                            .contains("JpaTransactionManager"));
                });
    }

    @Test
    void silentWhenAJpaStyleManagerExposesTheSameDataSource() {
        ListAppender<ILoggingEvent> log = capture();
        runner.withUserConfiguration(JpaStyleWithDataSourceConfig.class)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(warnings(log)).isEmpty();
                });
    }

    @Test
    void silentWhenNoTransactionManagerIsConfigured() {
        // No Spring-managed transactions at all: there is no participation to lose.
        ListAppender<ILoggingEvent> log = capture();
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(warnings(log)).isEmpty();
        });
    }

    @Configuration
    static class DataSourceTxManagerConfig {
        @Bean
        DataSourceTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }

    @Configuration
    static class JpaStyleWithDataSourceConfig {
        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new JpaStyleTransactionManager(dataSource);
        }
    }

    /**
     * A stand-in for {@code JpaTransactionManager} (not on tieto-spring's classpath): it exposes a
     * nullable {@code getDataSource()} reached reflectively by the guard, and is not a
     * {@code ResourceTransactionManager} over a DataSource.
     */
    static final class JpaStyleTransactionManager implements PlatformTransactionManager {
        private final DataSource dataSource;

        JpaStyleTransactionManager(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        @SuppressWarnings("unused")   // reached reflectively by the guard
        public DataSource getDataSource() {
            return dataSource;
        }

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            throw new UnsupportedOperationException("not used by the guard");
        }

        @Override
        public void commit(TransactionStatus status) {
            throw new UnsupportedOperationException("not used by the guard");
        }

        @Override
        public void rollback(TransactionStatus status) {
            throw new UnsupportedOperationException("not used by the guard");
        }
    }

    private static java.util.List<String> warnings(ListAppender<ILoggingEvent> log) {
        return log.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private static ListAppender<ILoggingEvent> capture() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(TietoTransactionParticipationGuard.class);
        logger.setLevel(Level.DEBUG);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }
}
