package net.unit8.tieto.core.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A sealed hierarchy reached through a <em>non-sealed</em> container (a criteria
 * record holding a {@code Specification} field, or one sealed hierarchy embedded
 * in an unrelated one) must still carry the {@code "kind"} discriminator and
 * round-trip. Previously the discriminator was only registered when the sealed
 * type was the top-level parameter/return type.
 */
class NestedSealedMappingTest {

    private final ConventionMapper conventionMapper = new ConventionMapper();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void sealedFieldInNonSealedRecord_getsKindAndRoundTrips() throws Exception {
        DomainMapper<OrderCriteria> mapper = conventionMapper.forType(OrderCriteria.class);

        OrderCriteria criteria = new OrderCriteria(new ForCustomer("CUST-001"), 10);
        String serialized = mapper.toJson(criteria);

        JsonNode node = json.readTree(serialized);
        assertThat(node.has("kind")).as("the non-sealed container itself").isFalse();
        assertThat(node.get("spec").get("kind").asText()).isEqualTo("forCustomer");
        assertThat(node.get("limit").asInt()).isEqualTo(10);

        OrderCriteria back = mapper.fromJson(serialized, OrderCriteria.class);
        assertThat(back).isEqualTo(criteria);
    }

    @Test
    void sealedListInNonSealedRecord_eachElementGetsKind() throws Exception {
        DomainMapper<OrderCriteria2> mapper = conventionMapper.forType(OrderCriteria2.class);

        OrderCriteria2 criteria = new OrderCriteria2(List.of(
                new ForCustomer("CUST-001"),
                new HighValue(new BigDecimal("1000"))));
        String serialized = mapper.toJson(criteria);

        JsonNode specs = json.readTree(serialized).get("specs");
        assertThat(specs.get(0).get("kind").asText()).isEqualTo("forCustomer");
        assertThat(specs.get(1).get("kind").asText()).isEqualTo("highValue");

        OrderCriteria2 back = mapper.fromJson(serialized, OrderCriteria2.class);
        assertThat(back).isEqualTo(criteria);
    }

    @Test
    void nestedCompositeTreeInNonSealedRecord_roundTrips() throws Exception {
        DomainMapper<OrderCriteria> mapper = conventionMapper.forType(OrderCriteria.class);

        OrderCriteria criteria = new OrderCriteria(
                new And(List.of(new ForCustomer("C1"), new Not(new HighValue(new BigDecimal("5"))))),
                3);
        String serialized = mapper.toJson(criteria);

        OrderCriteria back = mapper.fromJson(serialized, OrderCriteria.class);
        assertThat(back).isEqualTo(criteria);
    }

    // Non-sealed containers reaching the sealed hierarchy through a field.
    record OrderCriteria(OrderSpec spec, int limit) {}
    record OrderCriteria2(List<OrderSpec> specs) {}

    // Specification hierarchy — plain records, zero annotations.
    sealed interface OrderSpec permits And, Not, ForCustomer, HighValue {}
    record And(List<OrderSpec> specs) implements OrderSpec {}
    record Not(OrderSpec spec) implements OrderSpec {}
    record ForCustomer(String customerId) implements OrderSpec {}
    record HighValue(BigDecimal min) implements OrderSpec {}
}
