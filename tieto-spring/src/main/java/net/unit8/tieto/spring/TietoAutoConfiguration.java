package net.unit8.tieto.spring;

import net.unit8.tieto.core.TietoClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

/**
 * Auto-configuration for tieto Spring integration.
 *
 * <p>Automatically creates a {@link TietoClient} bean backed by a
 * {@link SpringConnectionProvider} when a {@link DataSource} is available.</p>
 */
@AutoConfiguration
@ConditionalOnClass(TietoClient.class)
@EnableConfigurationProperties(TietoProperties.class)
public class TietoAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TietoClient tietoClient(DataSource dataSource) {
        return TietoClient.builder(new SpringConnectionProvider(dataSource)).build();
    }
}
