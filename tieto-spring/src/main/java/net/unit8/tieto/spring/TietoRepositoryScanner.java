package net.unit8.tieto.spring;

import net.unit8.tieto.core.annotation.TietoRepository;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

/**
 * Scans base packages for interfaces annotated with {@link TietoRepository} and
 * registers a {@link TietoRepositoryFactoryBean} for each. Shared by the
 * annotation-driven {@link TietoRepositoryRegistrar} and the property-driven path
 * in {@link TietoAutoConfiguration}.
 */
final class TietoRepositoryScanner {

    private TietoRepositoryScanner() {
    }

    static void register(BeanDefinitionRegistry registry, Iterable<String> basePackages) {
        // The default candidate check rejects interfaces (it wants concrete, independent
        // classes); override it to accept top-level interfaces. The include filter then
        // narrows to those carrying @TietoRepository, so unrelated interfaces are skipped.
        var scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                var metadata = beanDefinition.getMetadata();
                return metadata.isInterface() && metadata.isIndependent();
            }
        };
        scanner.addIncludeFilter(new AnnotationTypeFilter(TietoRepository.class));

        for (String basePackage : basePackages) {
            if (basePackage == null || basePackage.isBlank()) {
                continue;
            }
            for (BeanDefinition candidate : scanner.findCandidateComponents(basePackage)) {
                String beanClassName = candidate.getBeanClassName();
                if (beanClassName == null) {
                    continue;
                }
                String beanName = beanNameFor(beanClassName);
                // Idempotent: a repository reachable from two base packages (or from both
                // the annotation and the property path) is registered once.
                if (registry.containsBeanDefinition(beanName)) {
                    continue;
                }
                BeanDefinition factoryBeanDef = BeanDefinitionBuilder
                        .genericBeanDefinition(TietoRepositoryFactoryBean.class)
                        .addConstructorArgValue(beanClassName)
                        .setAutowireMode(AbstractBeanDefinition.AUTOWIRE_BY_TYPE)
                        .getBeanDefinition();
                registry.registerBeanDefinition(beanName, factoryBeanDef);
            }
        }
    }

    private static String beanNameFor(String beanClassName) {
        String simpleName = beanClassName.substring(beanClassName.lastIndexOf('.') + 1);
        return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
    }
}
