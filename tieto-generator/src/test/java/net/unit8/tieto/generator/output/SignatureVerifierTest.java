package net.unit8.tieto.generator.output;

import net.unit8.tieto.generator.output.SignatureVerifier.ExpectedSignature;
import net.unit8.tieto.generator.parser.MethodSpec;
import net.unit8.tieto.generator.parser.ParameterSpec;
import net.unit8.tieto.generator.parser.TypeDef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SignatureVerifierTest {

    private static ParameterSpec simple(String name, String type) {
        return new ParameterSpec(name, type, null);
    }

    private static ParameterSpec domain(String name, String type) {
        return new ParameterSpec(name, type,
                new TypeDef(type, type, null, false, "", List.of(), List.of()));
    }

    private static ParameterSpec spec(String name, String type) {
        return new ParameterSpec(name, type,
                new TypeDef(type, type, null, true, "", List.of(), List.of()));
    }

    private static MethodSpec method(String name, String returnType, ParameterSpec... params) {
        return new MethodSpec(name, returnType, List.of(params), "", 1);
    }

    @Test
    void optionalReturnIsSingleNonVoid() {
        ExpectedSignature s = SignatureVerifier.expectedFor(
                method("findById", "Optional<Order>", simple("id", "Long")));
        assertThat(s).isEqualTo(new ExpectedSignature(false, false, 1));
    }

    @Test
    void listReturnIsSetof() {
        ExpectedSignature s = SignatureVerifier.expectedFor(
                method("findByCustomerId", "List<Order>", simple("customerId", "String")));
        assertThat(s).isEqualTo(new ExpectedSignature(true, false, 1));
    }

    @Test
    void qualifiedListReturnIsAlsoSetof() {
        ExpectedSignature s = SignatureVerifier.expectedFor(
                method("findAll", "java.util.List<Order>"));
        assertThat(s.returnsSet()).isTrue();
        assertThat(s.argCount()).isZero();
    }

    @Test
    void setReturnIsNotSetof() {
        // tieto-core supports only List<T> as a collection return, so Set<T> is not SETOF.
        ExpectedSignature s = SignatureVerifier.expectedFor(method("findActive", "Set<Order>"));
        assertThat(s.returnsSet()).isFalse();
    }

    @Test
    void voidMethodReturnsVoid() {
        ExpectedSignature s = SignatureVerifier.expectedFor(
                method("save", "void", domain("order", "Order")));
        assertThat(s).isEqualTo(new ExpectedSignature(false, true, 1));
    }

    @Test
    void scalarReturnIsSingleNonVoid() {
        ExpectedSignature s = SignatureVerifier.expectedFor(method("count", "long"));
        assertThat(s).isEqualTo(new ExpectedSignature(false, false, 0));
    }

    @Test
    void specParameterIsCountedInArity() {
        ExpectedSignature s = SignatureVerifier.expectedFor(
                method("findBy", "List<Order>", spec("spec", "OrderSpec")));
        assertThat(s).isEqualTo(new ExpectedSignature(true, false, 1));
    }

    @Test
    void mixedSimpleAndDomainArgsCountArity() {
        ExpectedSignature s = SignatureVerifier.expectedFor(
                method("upsert", "Optional<Order>",
                        simple("id", "Long"), domain("order", "Order")));
        assertThat(s).isEqualTo(new ExpectedSignature(false, false, 2));
    }
}
