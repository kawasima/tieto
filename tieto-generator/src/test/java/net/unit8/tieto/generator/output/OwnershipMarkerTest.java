package net.unit8.tieto.generator.output;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OwnershipMarkerTest {

    @Test
    void emitsANameOnlyCommentForAPlainFunction() {
        String marker = OwnershipMarker.forFunction("OrderRepository", "findById", 1,
                "order_repository_find_by_id_v1", false);

        assertThat(marker).isEqualTo(
                "COMMENT ON FUNCTION order_repository_find_by_id_v1 IS "
                        + "'tieto:generated repository=OrderRepository method=findById version=1';");
        assertThat(marker).doesNotContain("(");   // no argument list — the name is unique per version
    }

    @Test
    void alsoCommentsTheSpecHelperWhenPresent() {
        String marker = OwnershipMarker.forFunction("OrderRepository", "findBy", 2,
                "order_repository_find_by_v2", true);

        assertThat(marker.lines()).hasSize(2);
        assertThat(marker).contains("COMMENT ON FUNCTION order_repository_find_by_v2 IS")
                .contains("COMMENT ON FUNCTION order_repository_find_by_v2_spec_to_sql IS")
                .contains("version=2");
    }

    @Test
    void isManagedRecognisesOnlyTheTietoMarker() {
        assertThat(OwnershipMarker.isManaged("tieto:generated repository=OrderRepository")).isTrue();
        assertThat(OwnershipMarker.isManaged("hand-written helper")).isFalse();
        assertThat(OwnershipMarker.isManaged(null)).isFalse();
    }
}
