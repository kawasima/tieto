package net.unit8.tieto.core.mapper;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConventionMapperTest {

    private final ConventionMapper conventionMapper = new ConventionMapper();

    @Test
    void roundTrip_record() {
        DomainMapper<SimpleOrder> mapper = conventionMapper.forType(SimpleOrder.class);
        var order = new SimpleOrder(1L, "CUST-001", "PENDING");

        String json = mapper.toJson(order);
        SimpleOrder result = mapper.fromJson(json, SimpleOrder.class);

        assertThat(result).isEqualTo(order);
    }

    @Test
    void roundTrip_nestedRecord() {
        DomainMapper<OrderWithLines> mapper = conventionMapper.forType(OrderWithLines.class);
        var order = new OrderWithLines(
                1L,
                "CUST-001",
                List.of(
                        new OrderLine("PROD-A", 2, new BigDecimal("29.99")),
                        new OrderLine("PROD-B", 1, new BigDecimal("49.99"))
                )
        );

        String json = mapper.toJson(order);
        OrderWithLines result = mapper.fromJson(json, OrderWithLines.class);

        assertThat(result).isEqualTo(order);
        assertThat(result.lines()).hasSize(2);
    }

    @Test
    void roundTrip_withLocalDateTime() {
        DomainMapper<TimestampEntity> mapper = conventionMapper.forType(TimestampEntity.class);
        var entity = new TimestampEntity(1L, LocalDateTime.of(2024, 6, 15, 10, 30, 0));

        String json = mapper.toJson(entity);
        TimestampEntity result = mapper.fromJson(json, TimestampEntity.class);

        assertThat(result).isEqualTo(entity);
    }

    @Test
    void fromJson_ignoresUnknownProperties() {
        DomainMapper<SimpleOrder> mapper = conventionMapper.forType(SimpleOrder.class);
        String json = """
                {"id": 1, "customerId": "CUST-001", "status": "ACTIVE", "unknownField": "value"}
                """;

        SimpleOrder result = mapper.fromJson(json, SimpleOrder.class);
        assertThat(result.id()).isEqualTo(1L);
        assertThat(result.customerId()).isEqualTo("CUST-001");
    }

    @Test
    void roundTrip_withEnum() {
        DomainMapper<EnumEntity> mapper = conventionMapper.forType(EnumEntity.class);
        var entity = new EnumEntity(1L, Status.ACTIVE);

        String json = mapper.toJson(entity);
        EnumEntity result = mapper.fromJson(json, EnumEntity.class);

        assertThat(result).isEqualTo(entity);
    }

    // Test types
    record SimpleOrder(Long id, String customerId, String status) {}
    record OrderLine(String productId, int quantity, BigDecimal unitPrice) {}
    record OrderWithLines(Long id, String customerId, List<OrderLine> lines) {}
    record TimestampEntity(Long id, LocalDateTime createdAt) {}
    enum Status { ACTIVE, INACTIVE }
    record EnumEntity(Long id, Status status) {}
}
