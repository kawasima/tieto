package net.unit8.tieto.spring;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

/**
 * Registers tieto repositories from the {@code tieto.base-packages} property,
 * letting repositories be enabled through configuration alone — without an
 * {@link EnableTietoRepositories} annotation.
 *
 * <p>Runs as a {@link BeanDefinitionRegistryPostProcessor} so the factory beans
 * are in place before autowiring; the base packages are bound straight from the
 * {@link Environment} since the {@link TietoProperties} bean is not available this
 * early.</p>
 */
final class TietoPropertyRepositoryRegistrar
        implements BeanDefinitionRegistryPostProcessor, EnvironmentAware {

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        TietoProperties properties = Binder.get(environment)
                .bind("tieto", TietoProperties.class)
                .orElseGet(TietoProperties::new);
        TietoRepositoryScanner.register(registry, properties.getBasePackages());
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        // No bean factory post-processing needed.
    }
}
