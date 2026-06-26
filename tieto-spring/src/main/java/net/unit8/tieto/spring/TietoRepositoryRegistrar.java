package net.unit8.tieto.spring;

import net.unit8.tieto.core.annotation.TietoRepository;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.util.ClassUtils;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Registers a {@link TietoRepositoryFactoryBean} for every {@link TietoRepository}-annotated
 * interface found in the base packages declared by {@link EnableTietoRepositories}.
 *
 * <p>Base packages are taken from {@code value}/{@code basePackages} and from the
 * packages of {@code basePackageClasses}; when none are given, the package of the
 * annotated configuration class is scanned.</p>
 */
public final class TietoRepositoryRegistrar implements ImportBeanDefinitionRegistrar {

    @Override
    public void registerBeanDefinitions(
            AnnotationMetadata importingClassMetadata,
            BeanDefinitionRegistry registry) {

        MergedAnnotation<EnableTietoRepositories> annotation = importingClassMetadata
                .getAnnotations()
                .get(EnableTietoRepositories.class);
        if (!annotation.isPresent()) {
            return;
        }

        Set<String> basePackages = new LinkedHashSet<>();
        Collections.addAll(basePackages, annotation.getStringArray("basePackages"));
        for (Class<?> markerClass : annotation.getClassArray("basePackageClasses")) {
            basePackages.add(markerClass.getPackageName());
        }
        basePackages.removeIf(pkg -> pkg == null || pkg.isBlank());

        if (basePackages.isEmpty()) {
            // Mirror @ComponentScan: default to the package of the annotated class.
            basePackages.add(ClassUtils.getPackageName(importingClassMetadata.getClassName()));
        }

        TietoRepositoryScanner.register(registry, basePackages);
    }
}
