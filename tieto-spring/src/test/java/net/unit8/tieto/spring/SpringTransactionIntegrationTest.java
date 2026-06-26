package net.unit8.tieto.spring;

import net.unit8.tieto.core.TietoClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the Connection tieto acquires per function call participates in
 * the surrounding Spring-managed transaction.
 *
 * <p>{@link TietoAutoConfiguration} wraps the DataSource in a
 * {@code TransactionAwareDataSourceProxy}, so a tieto write done inside a
 * {@code @Transactional} boundary must commit or roll back with that boundary —
 * not on its own connection.</p>
 */
@SpringJUnitConfig(SpringTransactionIntegrationTest.Config.class)
@Testcontainers
class SpringTransactionIntegrationTest {

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
    TransactionTemplate tx;

    @Test
    void writeRolledBackWithTheTransactionIsNotVisible() {
        long before = repo.count();

        tx.executeWithoutResult(status -> {
            repo.insert("rollback-me");
            status.setRollbackOnly();
        });

        assertThat(repo.count())
                .as("a tieto write inside a Spring transaction must roll back with it")
                .isEqualTo(before);
    }

    @Test
    void writeCommittedWithTheTransactionIsVisible() {
        long before = repo.count();

        tx.executeWithoutResult(status -> repo.insert("commit-me"));

        assertThat(repo.count())
                .as("a committed tieto write inside a Spring transaction must persist")
                .isEqualTo(before + 1);
    }

    interface WidgetRepository {
        void insert(String name);
        long count();
    }

    @Configuration
    @EnableTransactionManagement
    @Import(TietoAutoConfiguration.class)
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
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
            return new TransactionTemplate(transactionManager);
        }

        @Bean
        WidgetRepository widgetRepository(TietoClient tietoClient) {
            return tietoClient.createRepository(WidgetRepository.class);
        }
    }
}
