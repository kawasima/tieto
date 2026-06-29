package net.unit8.tieto.spring;

import net.unit8.tieto.core.TietoClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies tieto's transaction participation through Spring's {@code @Transactional}
 * AOP interceptor path (not just {@code TransactionTemplate}), including exception-driven
 * rollback and the {@code REQUIRES_NEW} / {@code NESTED} propagation modes.
 *
 * <p>The propagation cases route through a second bean so the inner {@code @Transactional}
 * call crosses a proxy boundary rather than being a self-invocation that AOP cannot intercept.</p>
 */
@SpringJUnitConfig(SpringAopTransactionIntegrationTest.Config.class)
@Testcontainers(disabledWithoutDocker = true)
class SpringAopTransactionIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("test.db.url", POSTGRES::getJdbcUrl);
        registry.add("test.db.user", POSTGRES::getUsername);
        registry.add("test.db.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void deploySchema() throws SQLException {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE widgets (name text)");
            stmt.execute("""
                    CREATE FUNCTION widget_repository_insert_v1(p_name text)
                    RETURNS void LANGUAGE sql AS $$ INSERT INTO widgets(name) VALUES (p_name) $$
                    """);
            stmt.execute("""
                    CREATE FUNCTION widget_repository_count_v1()
                    RETURNS jsonb LANGUAGE sql AS $$ SELECT to_jsonb(count(*)) FROM widgets $$
                    """);
        }
    }

    @Autowired
    WidgetRepository repo;

    @Autowired
    WidgetService service;

    @Test
    void aopTransactionalMethodCommits() {
        long before = repo.count();

        service.insert("aop-commit");

        assertThat(repo.count())
                .as("an @Transactional method driven via the AOP proxy must commit its tieto write")
                .isEqualTo(before + 1);
    }

    @Test
    void aopTransactionalMethodRollsBackOnException() {
        long before = repo.count();

        assertThatThrownBy(() -> service.insertThenThrow("aop-rollback"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(repo.count())
                .as("an exception out of an @Transactional method must roll the tieto write back")
                .isEqualTo(before);
    }

    @Test
    void requiresNewInnerCommitsEvenWhenOuterRollsBack() {
        long before = repo.count();

        assertThatThrownBy(() ->
                service.outerThrowsAfterInnerRequiresNewCommits("rn-inner", "rn-outer"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(repo.count())
                .as("REQUIRES_NEW inner write commits independently; the rolled-back outer write does not")
                .isEqualTo(before + 1);
    }

    @Test
    void nestedInnerRollsBackToSavepointWhileOuterCommits() {
        long before = repo.count();

        service.outerCommitsAfterNestedRollsBack("nested-outer", "nested-inner");

        assertThat(repo.count())
                .as("NESTED inner write rolls back to its savepoint; the outer write still commits")
                .isEqualTo(before + 1);
    }

    interface WidgetRepository {
        void insert(String name);
        long count();
    }

    /** Outer service whose methods are driven through the {@code @Transactional} AOP proxy. */
    static class WidgetService {
        private final WidgetRepository repo;
        private final InnerWidgetService inner;

        WidgetService(WidgetRepository repo, InnerWidgetService inner) {
            this.repo = repo;
            this.inner = inner;
        }

        @Transactional
        public void insert(String name) {
            repo.insert(name);
        }

        @Transactional
        public void insertThenThrow(String name) {
            repo.insert(name);
            throw new IllegalStateException("boom");
        }

        @Transactional
        public void outerThrowsAfterInnerRequiresNewCommits(String innerName, String outerName) {
            inner.insertRequiresNew(innerName);
            repo.insert(outerName);
            throw new IllegalStateException("outer boom");
        }

        @Transactional
        public void outerCommitsAfterNestedRollsBack(String outerName, String nestedName) {
            repo.insert(outerName);
            try {
                inner.insertNestedThenThrow(nestedName);
            } catch (IllegalStateException ignored) {
                // swallow so the outer transaction still commits; the NESTED write
                // rolls back to its savepoint without dooming the outer transaction.
            }
        }
    }

    /** A separate bean so the inner propagation boundary crosses the AOP proxy. */
    static class InnerWidgetService {
        private final WidgetRepository repo;

        InnerWidgetService(WidgetRepository repo) {
            this.repo = repo;
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void insertRequiresNew(String name) {
            repo.insert(name);
        }

        @Transactional(propagation = Propagation.NESTED)
        public void insertNestedThenThrow(String name) {
            repo.insert(name);
            throw new IllegalStateException("nested boom");
        }
    }

    @Configuration
    @EnableTransactionManagement
    @ImportAutoConfiguration(TietoAutoConfiguration.class)
    static class Config {

        @Bean
        DataSource dataSource(Environment env) {
            PGSimpleDataSource ds = new PGSimpleDataSource();
            ds.setUrl(env.getProperty("test.db.url"));
            ds.setUser(env.getProperty("test.db.user"));
            ds.setPassword(env.getProperty("test.db.password"));
            return ds;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
            manager.setNestedTransactionAllowed(true); // enable NESTED (savepoints)
            return manager;
        }

        @Bean
        WidgetRepository widgetRepository(TietoClient tietoClient) {
            return tietoClient.createRepository(WidgetRepository.class);
        }

        @Bean
        InnerWidgetService innerWidgetService(WidgetRepository repo) {
            return new InnerWidgetService(repo);
        }

        @Bean
        WidgetService widgetService(WidgetRepository repo, InnerWidgetService inner) {
            return new WidgetService(repo, inner);
        }
    }
}
