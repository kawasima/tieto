package net.unit8.tieto.spring.testrepos;

import net.unit8.tieto.core.annotation.TietoRepository;

import java.util.Optional;

/**
 * A tieto repository fixture: carries {@link TietoRepository} so scanning picks it up.
 */
@TietoRepository
public interface SampleRepository {
    Optional<String> findById(String id);
}
