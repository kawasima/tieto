package net.unit8.tieto.generator.command;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GenerateCommand#urlEmbedsPassword(String)} detects a password embedded in a JDBC
 * URL (so the CLI can warn), without flagging a bare username or a credential-free URL.
 */
class DbUrlPasswordTest {

    @Test
    void flagsUserinfoWithPassword() {
        assertThat(GenerateCommand.urlEmbedsPassword(
                "jdbc:postgresql://tieto:secret@localhost:5432/tieto_example")).isTrue();
    }

    @Test
    void flagsPasswordQueryParameter() {
        assertThat(GenerateCommand.urlEmbedsPassword(
                "jdbc:postgresql://localhost:5432/tieto_example?user=tieto&password=secret")).isTrue();
    }

    @Test
    void doesNotFlagABareUsername() {
        assertThat(GenerateCommand.urlEmbedsPassword(
                "jdbc:postgresql://tieto@localhost:5432/tieto_example")).isFalse();
    }

    @Test
    void doesNotFlagACredentialFreeUrl() {
        assertThat(GenerateCommand.urlEmbedsPassword(
                "jdbc:postgresql://localhost:5432/tieto_example")).isFalse();
    }

    @Test
    void handlesNull() {
        assertThat(GenerateCommand.urlEmbedsPassword(null)).isFalse();
    }
}
