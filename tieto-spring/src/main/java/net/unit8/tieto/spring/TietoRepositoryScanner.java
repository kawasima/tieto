package net.unit8.tieto.spring;

import net.unit8.tieto.core.annotation.TietoRepository;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConstructorArgumentValues.ValueHolder;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.beans.Introspector;

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
        // classes); override it to accept interfaces. The include filter then narrows to
        // those carrying @TietoRepository, so unrelated interfaces are skipped.
        var scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                return beanDefinition.getMetadata().isInterface();
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
                registerRepository(registry, beanClassName);
            }
        }
    }

    private static void registerRepository(BeanDefinitionRegistry registry, String beanClassName) {
        String beanName = beanNameFor(beanClassName);
        if (registry.containsBeanDefinition(beanName)) {
            if (isSameRepository(registry.getBeanDefinition(beanName), beanClassName)) {
                // Same repository reached via two base packages (or both the annotation and
                // the property path): registering once is enough.
                return;
            }
            // A different bean already owns this name. Fail loudly rather than silently
            // dropping the repository, which would surface far away as a missing bean.
            throw new IllegalStateException(
                    "tieto repository '" + beanClassName + "' maps to bean name '" + beanName
                            + "', which is already taken by another bean definition. "
                            + "Rename the interface or the conflicting bean.");
        }
        BeanDefinition factoryBeanDef = BeanDefinitionBuilder
                .genericBeanDefinition(TietoRepositoryFactoryBean.class)
                .addConstructorArgValue(beanClassName)
                .setAutowireMode(AbstractBeanDefinition.AUTOWIRE_BY_TYPE)
                .getBeanDefinition();
        registry.registerBeanDefinition(beanName, factoryBeanDef);
    }

    private static boolean isSameRepository(BeanDefinition existing, String beanClassName) {
        if (!TietoRepositoryFactoryBean.class.getName().equals(existing.getBeanClassName())) {
            return false;
        }
        ValueHolder arg = existing.getConstructorArgumentValues().getArgumentValue(0, null);
        return arg != null && beanClassName.equals(arg.getValue());
    }

    private static String beanNameFor(String beanClassName) {
        String simpleName = beanClassName.substring(beanClassName.lastIndexOf('.') + 1);
        // Match Spring's default bean naming (JavaBeans decapitalization), which leaves
        // acronym prefixes intact: URLRepository -> URLRepository, not uRLRepository.
        return Introspector.decapitalize(simpleName);
    }
}
