package net.unit8.tieto.spring;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the Spring Boot auto-configuration registration. Without the
 * {@code META-INF/spring/...AutoConfiguration.imports} entry, {@link TietoAutoConfiguration}
 * never activates under {@code @SpringBootApplication}: neither the {@code tietoClient}
 * bean nor property-driven registration ({@code tieto.base-packages}) would work in a real app.
 */
class TietoAutoConfigurationImportsTest {

    private static final String IMPORTS =
            "/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    @Test
    void autoConfigurationIsRegisteredForBootDiscovery() throws IOException {
        try (InputStream in = getClass().getResourceAsStream(IMPORTS)) {
            assertThat(in).as("auto-configuration imports file must exist").isNotNull();
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(content.lines().map(String::trim))
                    .contains(TietoAutoConfiguration.class.getName());
        }
    }
}
