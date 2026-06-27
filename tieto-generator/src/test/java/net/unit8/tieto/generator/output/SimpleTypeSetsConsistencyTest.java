package net.unit8.tieto.generator.output;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards against drift between the two views of "which simple parameter types we
 * can synthesize": the deploy-time read smoke ({@link ReadSmokeVerifier}) and the
 * emitted round-trip test ({@link RepositoryTestGenerator}). If they diverge, a
 * finder the smoke runs would be emitted as a @Disabled scaffold (or vice versa).
 */
class SimpleTypeSetsConsistencyTest {

    @Test
    void synthesizableAndRenderableTypeSetsMatch() {
        assertThat(RepositoryTestGenerator.renderableTypeNames())
                .containsExactlyInAnyOrderElementsOf(ReadSmokeVerifier.synthesizableTypeNames());
    }
}
