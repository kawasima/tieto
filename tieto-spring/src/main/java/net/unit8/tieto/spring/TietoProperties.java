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

    public List<String> getBasePackages() {
        return basePackages;
    }

    public void setBasePackages(List<String> basePackages) {
        this.basePackages = basePackages;
    }
}
