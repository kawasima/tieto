package net.unit8.tieto.generator.output;

import com.github.javaparser.StaticJavaParser;
import net.unit8.tieto.generator.parser.ComponentDef;
import net.unit8.tieto.generator.parser.MethodSpec;
import net.unit8.tieto.generator.parser.ParameterSpec;
import net.unit8.tieto.generator.parser.TypeDef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class RepositoryTestGeneratorTest {

    private static final TypeDef ORDER = new TypeDef("Order", "Order", null, false, "",
            List.of(new ComponentDef("id", "Long"), new ComponentDef("customerId", "String")),
            List.of());

    private static ParameterSpec simple(String name, String type) {
        return new ParameterSpec(name, type, null);
    }

    private static MethodSpec method(String name, String returnType, ParameterSpec... params) {
        return new MethodSpec(name, returnType, List.of(params), "", 1);
    }

    private String generate(List<MethodSpec> methods) {
        RepositoryTestGenerator.Options options = new RepositoryTestGenerator.Options(
                "com.example.domain", "OrderRepository",
                "tieto/order_repository_functions.sql", "db/01_schema.sql", "db/02_testdata.sql");
        return new RepositoryTestGenerator().generate(methods, options);
    }

    @Test
    void emitsPackageClassAndBootstrap() {
        String src = generate(List.of(method("findAll", "List<Order>")));
        assertThat(src).contains("package com.example.domain;");
        assertThat(src).contains("class OrderRepositoryRoundTripTest {");
        assertThat(src).contains("@Testcontainers");
        assertThat(src).contains("new PostgreSQLContainer<>(\"postgres:16\")");
        assertThat(src).contains("TietoClient.builder(dataSource).build()");
        assertThat(src).contains(".createRepository(OrderRepository.class)");
        assertThat(src).contains("\"db/01_schema.sql\"");
        assertThat(src).contains("\"db/02_testdata.sql\"");
        assertThat(src).contains("\"tieto/order_repository_functions.sql\"");
    }

    @Test
    void emitsAutomaticSmokeForSimpleArgFinder() {
        String src = generate(List.of(method("findById", "Optional<Order>", simple("id", "Long"))));
        assertThat(src).contains("void findById_smoke()");
        assertThat(src).contains("assertThat(repository.findById(0L)).isNotNull();");
    }

    @Test
    void emitsStringLiteralArg() {
        String src = generate(List.of(
                method("findByCustomerId", "List<Order>", simple("customerId", "String"))));
        assertThat(src).contains("repository.findByCustomerId(\"tieto-smoke\")");
    }

    @Test
    void emitsDisabledRoundTripForSavePairedWithFinder() {
        String src = generate(List.of(
                method("save", "void", new ParameterSpec("order", "Order", ORDER)),
                method("findByCustomerId", "List<Order>", simple("customerId", "String"))));
        assertThat(src).contains("@Disabled");
        assertThat(src).contains("void save_roundTrip()");
        assertThat(src).contains("Order saved = null;");
        assertThat(src).contains("repository.save(saved);");
        // Paired readback: finder reads by the saved object's customerId component.
        assertThat(src).contains("assertThat(repository.findByCustomerId(saved.customerId())).contains(saved);");
    }

    @Test
    void emitsDisabledScaffoldForSpecFinder() {
        MethodSpec findBy = method("findBy", "List<Order>",
                new ParameterSpec("spec", "OrderSpec",
                        new TypeDef("OrderSpec", "OrderSpec", null, true, "", List.of(), List.of())));
        String src = generate(List.of(findBy));
        assertThat(src).contains("@Disabled");
        assertThat(src).contains("void findBy_test()");
    }

    @Test
    void generatedSourceIsSyntacticallyValidJava() {
        String src = generate(List.of(
                method("findAll", "List<Order>"),
                method("findById", "Optional<Order>", simple("id", "Long")),
                method("count", "long"),
                method("findByCustomerId", "List<Order>", simple("customerId", "String")),
                method("save", "void", new ParameterSpec("order", "Order", ORDER)),
                method("findBy", "List<Order>", new ParameterSpec("spec", "OrderSpec",
                        new TypeDef("OrderSpec", "OrderSpec", null, true, "", List.of(), List.of()))),
                method("findSince", "List<Order>", simple("t", "java.time.Instant"))));
        assertThatCode(() -> StaticJavaParser.parse(src)).doesNotThrowAnyException();
    }

    @Test
    void multiArgWriteGoesToScaffoldNotSaveRoundTrip() {
        // updateStatus(Long, OrderStatus) is a write but not a single-aggregate write;
        // it must not be rendered as repository.updateStatus(saved) with one argument.
        MethodSpec updateStatus = method("updateStatus", "void",
                simple("id", "Long"),
                new ParameterSpec("status", "OrderStatus",
                        new TypeDef("OrderStatus", "OrderStatus", null, false, "", List.of(), List.of())));
        String src = generate(List.of(updateStatus));
        assertThat(src).contains("void updateStatus_test()");
        assertThat(src).doesNotContain("repository.updateStatus(saved)");
        assertThat(src).doesNotContain("OrderStatus saved = null;");
    }

    @Test
    void readbackPrefersANonIdComponent() {
        String src = generate(List.of(
                method("save", "void", new ParameterSpec("order", "Order", ORDER)),
                method("findById", "Optional<Order>", simple("id", "Long")),
                method("findByCustomerId", "List<Order>", simple("customerId", "String"))));
        assertThat(src).contains("repository.findByCustomerId(saved.customerId())");
        assertThat(src).doesNotContain("repository.findById(saved.id())");
    }

    @Test
    void overloadedMethodsGetUniqueTestNamesAndCompile() {
        String src = generate(List.of(
                method("findBy", "Optional<Order>", simple("id", "Long")),
                method("findBy", "Optional<Order>", simple("code", "String"))));
        assertThat(src).contains("void findBy_smoke()");
        assertThat(src).contains("void findBy_2_smoke()");
        // Both call the (overloaded) repository method; Java resolves by argument type.
        assertThat(src).contains("repository.findBy(0L)");
        assertThat(src).contains("repository.findBy(\"tieto-smoke\")");
        assertThatCode(() -> StaticJavaParser.parse(src)).doesNotThrowAnyException();
    }

    @Test
    void defaultPackageEmitsNoPackageStatement() {
        RepositoryTestGenerator.Options options = new RepositoryTestGenerator.Options(
                "", "OrderRepository",
                "tieto/order_repository_functions.sql", "db/01_schema.sql", "db/02_testdata.sql");
        String src = new RepositoryTestGenerator().generate(
                List.of(method("findAll", "List<Order>")), options);
        assertThat(src).doesNotContain("package ;");
        assertThat(src).doesNotStartWith("package");
        assertThatCode(() -> StaticJavaParser.parse(src)).doesNotThrowAnyException();
    }

    @Test
    void domainTypeInAnotherPackageIsReferencedByFqn() {
        TypeDef orderInModelPkg = new TypeDef("Order", "com.example.model.Order", null, false, "",
                List.of(new ComponentDef("customerId", "String")), List.of());
        String src = generate(List.of(
                method("save", "void", new ParameterSpec("order", "Order", orderInModelPkg))));
        assertThat(src).contains("com.example.model.Order saved = null;");
    }

    @Test
    void domainTypeInTheTestPackageUsesTheSimpleName() {
        // generate() uses test package "com.example.domain".
        TypeDef orderSamePkg = new TypeDef("Order", "com.example.domain.Order", null, false, "",
                List.of(new ComponentDef("customerId", "String")), List.of());
        String src = generate(List.of(
                method("save", "void", new ParameterSpec("order", "Order", orderSamePkg))));
        assertThat(src).contains("Order saved = null;");
        assertThat(src).doesNotContain("com.example.domain.Order saved");
    }

    @Test
    void saveWithoutAPairingFinderLeavesAReadbackTodo() {
        String src = generate(List.of(
                method("save", "void", new ParameterSpec("order", "Order", ORDER))));
        assertThat(src).contains("void save_roundTrip()");
        assertThat(src).contains("read it back via a finder");
    }
}
