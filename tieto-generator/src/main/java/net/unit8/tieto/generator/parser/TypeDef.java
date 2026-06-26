package net.unit8.tieto.generator.parser;

import java.util.List;

/**
 * A resolved domain type passed as a Repository method parameter.
 *
 * <p>For ordinary domain objects this carries the type's components. For a
 * sealed Specification hierarchy, {@link #sealed()} is {@code true} and
 * {@link #subtypes()} holds every permitted subtype (composites such as
 * {@code And}/{@code Or}/{@code Not} and the domain-named leaves), each with its
 * {@link #kind()} discriminator. The generator feeds this structure to the AI so
 * it can derive WHERE conditions from the domain model and emit a recursive
 * function that interprets the JSONB tree.</p>
 *
 * @param simpleName  the simple type name
 * @param kind        the JSONB {@code "kind"} discriminator (camelCase simple
 *                    name); meaningful for spec subtypes, {@code null} otherwise
 * @param sealed      whether this is the root of a sealed hierarchy
 * @param javadoc     the type's Javadoc description (may be empty)
 * @param components  record components / fields (name + type)
 * @param subtypes    permitted subtypes for a sealed hierarchy (empty otherwise)
 */
public record TypeDef(
        String simpleName,
        String kind,
        boolean sealed,
        String javadoc,
        List<ComponentDef> components,
        List<TypeDef> subtypes
) {}
