package net.unit8.tieto.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Configuration properties for tieto Spring integration.
 */
@ConfigurationProperties(prefix = "tieto")
public class TietoProperties {

    /**
     * Base packages to scan for Repository interfaces.
     */
    private List<String> basePackages = List.of();

    /**
     * Whether to link a {@code JpaTransactionManager} that has no {@code dataSource} of its own to
     * the application DataSource, so tieto's calls join the JPA {@code @Transactional} boundary.
     * Enabled by default; set to {@code false} to leave the transaction manager untouched.
     */
    private boolean linkJpaTransactionManager = true;

    public List<String> getBasePackages() {
        return basePackages;
    }

    public void setBasePackages(List<String> basePackages) {
        this.basePackages = basePackages;
    }

    public boolean isLinkJpaTransactionManager() {
        return linkJpaTransactionManager;
    }

    public void setLinkJpaTransactionManager(boolean linkJpaTransactionManager) {
        this.linkJpaTransactionManager = linkJpaTransactionManager;
    }
}
