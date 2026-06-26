package net.unit8.tieto.spring;

import net.unit8.tieto.core.TietoClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;

import javax.sql.DataSource;

/**
 * Auto-configuration for tieto Spring integration.
 *
 * <p>Wraps the application {@link DataSource} in a
 * {@link TransactionAwareDataSourceProxy} so that the Connection tieto acquires
 * per call participates in the surrounding {@code @Transactional} boundary and
 * is released (not physically closed) while a transaction is active.</p>
 */
@AutoConfiguration
@ConditionalOnClass(TietoClient.class)
@EnableConfigurationProperties(TietoProperties.class)
public class TietoAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TietoClient tietoClient(DataSource dataSource) {
        return TietoClient.builder(new TransactionAwareDataSourceProxy(dataSource)).build();
    }
}
