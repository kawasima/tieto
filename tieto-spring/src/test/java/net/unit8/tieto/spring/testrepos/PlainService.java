package net.unit8.tieto.spring.testrepos;

/**
 * A non-tieto interface fixture in the same package: it must NOT be registered
 * as a tieto proxy because it lacks {@code @TietoRepository}.
 */
public interface PlainService {
    String greet();
}
