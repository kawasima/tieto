package net.unit8.tieto.spring;

import org.springframework.context.annotation.Import;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Enables scanning for tieto Repository interfaces and registers them as
 * Spring beans backed by PostgreSQL function proxies.
 *
 * <pre>{@code
 * @SpringBootApplication
 * @EnableTietoRepositories(basePackages = "com.example.domain")
 * public class MyApplication { }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import(TietoRepositoryRegistrar.class)
public @interface EnableTietoRepositories {

    /**
     * Base packages to scan for Repository interfaces.
     */
    String[] basePackages() default {};
}
