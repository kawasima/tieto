package net.unit8.tieto.spring;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;

import java.util.Map;

/**
 * Scans for Repository interfaces in the specified base packages and
 * registers a {@link TietoRepositoryFactoryBean} for each one.
 *
 * <p>A Repository interface is detected if it is an interface (non-class).
 * All interfaces found in the scanned packages are registered.</p>
 */
public class TietoRepositoryRegistrar implements ImportBeanDefinitionRegistrar {

    @Override
    public void registerBeanDefinitions(
            AnnotationMetadata importingClassMetadata,
            BeanDefinitionRegistry registry) {

        Map<String, Object> attrs = importingClassMetadata
                .getAnnotationAttributes(EnableTietoRepositories.class.getName());

        if (attrs == null) return;

        String[] basePackages = (String[]) attrs.get("basePackages");
        if (basePackages == null || basePackages.length == 0) return;

        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        // Include all interfaces — we register every interface found in the packages
        scanner.addIncludeFilter((metadataReader, metadataReaderFactory) -> {
            try {
                Class<?> clazz = Class.forName(metadataReader.getClassMetadata().getClassName());
                return clazz.isInterface();
            } catch (ClassNotFoundException e) {
                return false;
            }
        });

        for (String basePackage : basePackages) {
            for (BeanDefinition candidate : scanner.findCandidateComponents(basePackage)) {
                String beanClassName = candidate.getBeanClassName();
                if (beanClassName == null) continue;

                BeanDefinition factoryBeanDef = BeanDefinitionBuilder
                        .genericBeanDefinition(TietoRepositoryFactoryBean.class)
                        .addConstructorArgValue(beanClassName)
                        .setAutowireMode(AbstractBeanDefinition.AUTOWIRE_BY_TYPE)
                        .getBeanDefinition();

                // Use simple class name with first letter lowercase as bean name
                String simpleName = beanClassName.substring(beanClassName.lastIndexOf('.') + 1);
                String beanName = Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);

                registry.registerBeanDefinition(beanName, factoryBeanDef);
            }
        }
    }
}
