package net.unit8.tieto.generator.output;

import net.unit8.tieto.generator.parser.MethodSpec;
import net.unit8.tieto.generator.parser.ParameterSpec;
import net.unit8.tieto.generator.parser.TypeDef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReadSmokeVerifierTest {

    private static ParameterSpec simple(String name, String type) {
        return new ParameterSpec(name, type, null);
    }

    private static ParameterSpec domain(String name, String type) {
        return new ParameterSpec(name, type,
                new TypeDef(type, type, null, false, "", List.of(), List.of()));
    }

    private static MethodSpec method(String name, String returnType, ParameterSpec... params) {
        return new MethodSpec(name, returnType, List.of(params), "", 1);
    }

    @Test
    void aFinderWithSimpleArgsIsEligible() {
        MethodSpec m = method("findById", "Optional<Order>", simple("id", "Long"));
        assertThat(ReadSmokeVerifier.isEligible(m)).isTrue();
        assertThat(ReadSmokeVerifier.probeArguments(m)).containsExactly(0L);
    }

    @Test
    void aNoArgFinderIsEligible() {
        MethodSpec m = method("findAll", "List<Order>");
        assertThat(ReadSmokeVerifier.isEligible(m)).isTrue();
        assertThat(ReadSmokeVerifier.probeArguments(m)).isEmpty();
    }

    @Test
    void aSaveIsNotReadShaped() {
        MethodSpec m = method("save", "void", domain("order", "Order"));
        assertThat(ReadSmokeVerifier.isReadShaped(m)).isFalse();
        assertThat(ReadSmokeVerifier.isEligible(m)).isFalse();
    }

    @Test
    void aDeleteReturningCountIsNotReadShaped() {
        MethodSpec m = method("deleteByCustomerId", "int", simple("customerId", "String"));
        assertThat(ReadSmokeVerifier.isReadShaped(m)).isFalse();
    }

    @Test
    void aVoidMethodIsNotReadShaped() {
        assertThat(ReadSmokeVerifier.isReadShaped(method("touch", "void"))).isFalse();
    }

    @Test
    void aSpecFinderIsReadShapedButNotEligible() {
        MethodSpec m = method("findBy", "List<Order>",
                new ParameterSpec("spec", "OrderSpec",
                        new TypeDef("OrderSpec", "OrderSpec", null, true, "", List.of(), List.of())));
        assertThat(ReadSmokeVerifier.isReadShaped(m)).isTrue();
        assertThat(ReadSmokeVerifier.isEligible(m)).isFalse();
    }

    @Test
    void aFinderWithAnUnsupportedTemporalArgIsReadShapedButNotEligible() {
        MethodSpec m = method("findSince", "List<Order>", simple("t", "java.time.Instant"));
        assertThat(ReadSmokeVerifier.isReadShaped(m)).isTrue();
        assertThat(ReadSmokeVerifier.isEligible(m)).isFalse();
    }

    @Test
    void synthesizesValuesForCommonSimpleTypes() {
        MethodSpec m = method("q", "List<Order>",
                simple("a", "String"), simple("b", "int"), simple("c", "boolean"),
                simple("d", "java.util.UUID"));
        assertThat(ReadSmokeVerifier.probeArguments(m))
                .containsExactly("tieto-smoke", 0, false,
                        java.util.UUID.fromString("00000000-0000-0000-0000-000000000000"));
    }
}
