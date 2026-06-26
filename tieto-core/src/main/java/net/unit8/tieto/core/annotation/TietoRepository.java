package net.unit8.tieto.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an interface as a tieto Repository. Package scanning (the Spring
 * {@code @EnableTietoRepositories} annotation or the {@code tieto.base-packages}
 * property) registers a PostgreSQL-function-backed proxy only for interfaces
 * carrying this annotation, leaving unrelated interfaces alone.
 *
 * <p>It lives in tieto-core so repository interfaces stay framework-neutral —
 * the same interface works whether registered explicitly via
 * {@link net.unit8.tieto.core.TietoClient#createRepository(Class)} (where the
 * annotation is not required) or discovered by scanning.</p>
 *
 * <p>The annotation is not inherited: scanning matches only interfaces that
 * declare it directly, so a sub-interface that merely {@code extends} a marked
 * interface must carry its own {@code @TietoRepository}.</p>
 *
 * <pre>{@code
 * @TietoRepository
 * public interface OrderRepository {
 *     Optional<Order> findById(UUID id);
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface TietoRepository {
}
