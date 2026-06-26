package net.unit8.tieto.spring;

import net.unit8.tieto.core.annotation.TietoRepository;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Enables scanning for {@link TietoRepository}-annotated interfaces and registers
 * them as Spring beans backed by PostgreSQL function proxies.
 *
 * <pre>{@code
 * @SpringBootApplication
 * @EnableTietoRepositories("com.example.domain")
 * public class MyApplication { }
 * }</pre>
 *
 * <p>If no package is given, the package of the annotated class is scanned.
 * Packages may also be supplied via the {@code tieto.base-packages} property
 * without this annotation.</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import(TietoRepositoryRegistrar.class)
public @interface EnableTietoRepositories {

    /**
     * Alias for {@link #basePackages()}: lets the packages be declared without
     * the attribute name, e.g. {@code @EnableTietoRepositories("com.example")}.
     */
    @AliasFor("basePackages")
    String[] value() default {};

    /**
     * Base packages to scan for {@link TietoRepository}-annotated interfaces.
     */
    @AliasFor("value")
    String[] basePackages() default {};

    /**
     * Type-safe alternative to {@link #basePackages()}: the package of each
     * listed class is scanned. Prefer a marker or {@code package-info} class here.
     */
    Class<?>[] basePackageClasses() default {};
}
