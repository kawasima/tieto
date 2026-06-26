package net.unit8.tieto.dup.b;

import net.unit8.tieto.core.annotation.TietoRepository;

/** Same simple name as {@code dup.a.DupeRepository}: drives the collision test. */
@TietoRepository
public interface DupeRepository {
    String find(String id);
}
