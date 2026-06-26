package net.unit8.tieto.generator;

import net.unit8.tieto.generator.ai.PromptBuilder;
import net.unit8.tieto.generator.parser.MethodSpec;
import net.unit8.tieto.generator.parser.ParameterSpec;
import net.unit8.tieto.generator.parser.RepositoryParser;
import net.unit8.tieto.generator.parser.RepositorySpec;
import net.unit8.tieto.generator.parser.TypeDef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies that the generator resolves a sealed Specification hierarchy from
 * source and feeds it (with the JSONB tree convention and recursive-function
 * instructions) into the AI prompt.
 */
class SpecificationGenerationTest {

    private static final Path EXAMPLE_SRC = Path.of("../examples/vanilla/src/main/java");
    private static final String REPO = "net.unit8.tieto.example.domain.OrderRepository";

    private RepositorySpec repo;

    @BeforeEach
    void parse() {
        assumeTrue(Files.exists(EXAMPLE_SRC.resolve(REPO.replace('.', '/') + ".java")),
                "vanilla example sources not found");
        repo = new RepositoryParser().parse(EXAMPLE_SRC, REPO);
    }

    @Test
    void resolvesSealedSpecHierarchyWithAllSubtypes() {
        MethodSpec findBy = method("findBy");
        ParameterSpec spec = findBy.parameters().getFirst();

        TypeDef def = spec.typeDef();
        assertThat(def).isNotNull();
        assertThat(def.simpleName()).isEqualTo("OrderSpec");
        assertThat(def.sealed()).isTrue();
        assertThat(def.subtypes()).extracting(TypeDef::kind)
                .containsExactlyInAnyOrder(
                        "and", "or", "not",
                        "forCustomer", "hasStatus", "createdAfter", "highValue");
    }

    @Test
    void leafComponentsAreResolved() {
        TypeDef def = method("findBy").parameters().getFirst().typeDef();
        TypeDef highValue = def.subtypes().stream()
                .filter(s -> s.kind().equals("highValue")).findFirst().orElseThrow();

        assertThat(highValue.components()).hasSize(1);
        assertThat(highValue.components().getFirst().name()).isEqualTo("min");
        assertThat(highValue.components().getFirst().type()).isEqualTo("BigDecimal");
    }

    @Test
    void simpleParametersResolveToNull() {
        // findById(Long id) — simple type, no TypeDef
        assertThat(method("findById").parameters().getFirst().typeDef()).isNull();
    }

    @Test
    void promptIncludesSpecTreeAndRecursiveHelperInstruction() {
        String prompt = new PromptBuilder().build(repo, method("findBy"), List.of());

        assertThat(prompt)
                .contains("Specification Parameter: spec (OrderSpec)")
                .contains("kind=\"and\"")
                .contains("kind=\"highValue\"")
                .contains("order_repository_find_by_v1_spec_to_sql")
                .contains("quote_literal")
                // the hardened anti-injection guidance must be present
                .contains("NEVER concatenate")
                .contains("%L");
    }

    private MethodSpec method(String name) {
        return repo.methods().stream()
                .filter(m -> m.name().equals(name)).findFirst().orElseThrow();
    }
}
