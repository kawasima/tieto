package net.unit8.tieto.core.function;

import net.unit8.tieto.core.StubDataSource;
import net.unit8.tieto.core.TietoClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every type advertised as "simple" by {@link net.unit8.tieto.core.proxy.ParameterInfo}
 * must actually bind through the JDBC driver. {@code Instant}, {@code ZonedDateTime},
 * {@code Character} and {@code BigInteger} are not supported by pgjdbc's
 * {@code setObject}, so they must be converted at bind time; this test catches a
 * regression that would otherwise only surface in a user's repository at call time.
 */
@Testcontainers(disabledWithoutDocker = true)
class SimpleTypeBindingIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    private static final Instant INSTANT = Instant.parse("2020-01-02T03:04:05Z");

    interface TemporalRepository {
        Boolean instantMatches(Instant p);
        Boolean zonedMatches(ZonedDateTime p);
        Boolean charMatches(Character p);
        Boolean bigIntegerMatches(BigInteger p);
    }

    @BeforeAll
    static void deployFunctions() throws SQLException {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE FUNCTION temporal_repository_instant_matches_v1(p timestamptz)
                    RETURNS jsonb LANGUAGE sql AS
                    $$ SELECT to_jsonb(p = TIMESTAMPTZ '2020-01-02 03:04:05Z') $$
                    """);
            stmt.execute("""
                    CREATE FUNCTION temporal_repository_zoned_matches_v1(p timestamptz)
                    RETURNS jsonb LANGUAGE sql AS
                    $$ SELECT to_jsonb(p = TIMESTAMPTZ '2020-01-02 03:04:05Z') $$
                    """);
            stmt.execute("""
                    CREATE FUNCTION temporal_repository_char_matches_v1(p text)
                    RETURNS jsonb LANGUAGE sql AS $$ SELECT to_jsonb(p = 'A') $$
                    """);
            stmt.execute("""
                    CREATE FUNCTION temporal_repository_big_integer_matches_v1(p numeric)
                    RETURNS jsonb LANGUAGE sql AS
                    $$ SELECT to_jsonb(p = 123456789012345678901234567890) $$
                    """);
        }
    }

    private TemporalRepository repo() {
        return TietoClient.builder(StubDataSource.opening(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()))
                .build().createRepository(TemporalRepository.class);
    }

    @Test
    void instantBinds() {
        assertThat(repo().instantMatches(INSTANT)).isTrue();
    }

    @Test
    void zonedDateTimeBinds() {
        assertThat(repo().zonedMatches(INSTANT.atZone(ZoneId.of("America/New_York")))).isTrue();
    }

    @Test
    void characterBinds() {
        assertThat(repo().charMatches('A')).isTrue();
    }

    @Test
    void bigIntegerBinds() {
        assertThat(repo().bigIntegerMatches(new BigInteger("123456789012345678901234567890"))).isTrue();
    }
}
