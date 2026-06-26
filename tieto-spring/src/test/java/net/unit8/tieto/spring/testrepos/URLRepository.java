package net.unit8.tieto.spring.testrepos;

import net.unit8.tieto.core.annotation.TietoRepository;

/**
 * Acronym-prefixed fixture: its bean name must follow Spring's JavaBeans
 * decapitalization (URLRepository), not a naive first-char-lowercase (uRLRepository).
 */
@TietoRepository
public interface URLRepository {
    String lookup(String key);
}
