package net.unit8.tieto.core.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that composable Specification trees (sealed interface hierarchies)
 * serialize to JSONB with a convention-based {@code "kind"} discriminator,
 * without any annotation on the domain types.
 */
class SealedSpecMappingTest {

    private final ConventionMapper conventionMapper = new ConventionMapper();
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void leaf_getsKindTagAndComponentNames() throws Exception {
        DomainMapper<OrderSpec> mapper = conventionMapper.forType(OrderSpec.class);

        JsonNode node = json.readTree(mapper.toJson(new HighValue(new BigDecimal("1000"))));

        assertThat(node.get("kind").asText()).isEqualTo("highValue");
        assertThat(node.get("min").decimalValue()).isEqualByComparingTo("1000");
    }

    @Test
    void composite_serializesNestedTreeWithKindAtEveryNode() throws Exception {
        DomainMapper<OrderSpec> mapper = conventionMapper.forType(OrderSpec.class);
        OrderSpec spec = new And(List.of(
                new ForCustomer("CUST-001"),
                new HighValue(new BigDecimal("1000"))
        ));

        JsonNode node = json.readTree(mapper.toJson(spec));

        assertThat(node.get("kind").asText()).isEqualTo("and");
        JsonNode specs = node.get("specs");
        assertThat(specs).hasSize(2);
        assertThat(specs.get(0).get("kind").asText()).isEqualTo("forCustomer");
        assertThat(specs.get(0).get("customerId").asText()).isEqualTo("CUST-001");
        assertThat(specs.get(1).get("kind").asText()).isEqualTo("highValue");
    }

    @Test
    void not_wrapsSingleSpec() throws Exception {
        DomainMapper<OrderSpec> mapper = conventionMapper.forType(OrderSpec.class);
        OrderSpec spec = new Not(new ForCustomer("CUST-002"));

        JsonNode node = json.readTree(mapper.toJson(spec));

        assertThat(node.get("kind").asText()).isEqualTo("not");
        assertThat(node.get("spec").get("kind").asText()).isEqualTo("forCustomer");
    }

    @Test
    void deeplyNested_orInsideAndWithNot() throws Exception {
        DomainMapper<OrderSpec> mapper = conventionMapper.forType(OrderSpec.class);
        OrderSpec spec = new And(List.of(
                new CreatedAfter(LocalDateTime.of(2024, 6, 5, 0, 0)),
                new Or(List.of(
                        new ForCustomer("CUST-001"),
                        new Not(new HighValue(new BigDecimal("100")))
                ))
        ));

        JsonNode node = json.readTree(mapper.toJson(spec));

        assertThat(node.get("kind").asText()).isEqualTo("and");
        assertThat(node.get("specs").get(0).get("kind").asText()).isEqualTo("createdAfter");
        JsonNode or = node.get("specs").get(1);
        assertThat(or.get("kind").asText()).isEqualTo("or");
        assertThat(or.get("specs").get(1).get("kind").asText()).isEqualTo("not");
        assertThat(or.get("specs").get(1).get("spec").get("kind").asText()).isEqualTo("highValue");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void listOfSpecsParameter_eachElementGetsKind() throws Exception {
        // A `List<OrderSpec>` parameter: the element type drives the discriminator,
        // since the container type (List) carries no sealed hierarchy of its own.
        DomainMapper mapper = conventionMapper.forType((Class) List.class, OrderSpec.class);
        List<OrderSpec> specs = List.of(
                new ForCustomer("CUST-001"),
                new HighValue(new BigDecimal("1000"))
        );

        JsonNode node = json.readTree(mapper.toJson(specs));

        assertThat(node).hasSize(2);
        assertThat(node.get(0).get("kind").asText()).isEqualTo("forCustomer");
        assertThat(node.get(0).get("customerId").asText()).isEqualTo("CUST-001");
        assertThat(node.get(1).get("kind").asText()).isEqualTo("highValue");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void listOfSpecsParameter_nestedTreeGetsKindAtEveryNode() throws Exception {
        DomainMapper mapper = conventionMapper.forType((Class) List.class, OrderSpec.class);
        List<OrderSpec> specs = List.of(
                new And(List.of(new ForCustomer("CUST-001"), new Not(new HighValue(new BigDecimal("100")))))
        );

        JsonNode node = json.readTree(mapper.toJson(specs));

        JsonNode and = node.get(0);
        assertThat(and.get("kind").asText()).isEqualTo("and");
        assertThat(and.get("specs").get(0).get("kind").asText()).isEqualTo("forCustomer");
        assertThat(and.get("specs").get(1).get("kind").asText()).isEqualTo("not");
        assertThat(and.get("specs").get(1).get("spec").get("kind").asText()).isEqualTo("highValue");
    }

    @Test
    void regularDomainRecord_hasNoKindTag() throws Exception {
        DomainMapper<PlainOrder> mapper = conventionMapper.forType(PlainOrder.class);

        JsonNode node = json.readTree(mapper.toJson(new PlainOrder(1L, "CUST-001")));

        assertThat(node.has("kind")).isFalse();
    }

    // Specification hierarchy — plain records, zero annotations
    sealed interface OrderSpec permits And, Or, Not, ForCustomer, HighValue, CreatedAfter {}
    record And(List<OrderSpec> specs) implements OrderSpec {}
    record Or(List<OrderSpec> specs) implements OrderSpec {}
    record Not(OrderSpec spec) implements OrderSpec {}
    record ForCustomer(String customerId) implements OrderSpec {}
    record HighValue(BigDecimal min) implements OrderSpec {}
    record CreatedAfter(LocalDateTime t) implements OrderSpec {}

    record PlainOrder(Long id, String customerId) {}
}
