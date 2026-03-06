package net.unit8.tieto.core.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Specifies the version of the PostgreSQL function generated for this method.
 * The version is appended to the function name as a suffix (e.g., {@code _v2}).
 *
 * <p>If not present, the method defaults to version 1.</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface FunctionVersion {
    int value() default 1;
}
