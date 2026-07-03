# tieto Spring Boot Example

Spring Boot with `@Transactional` and auto-wired repositories. The Repository functions are
**committed** as `src/main/resources/db/order_repository.sql` and loaded into PostgreSQL at startup
by docker-compose (it mounts `src/main/resources/db` into the container's init directory), so you
can run the app without any AI generation step.

## Run

```bash
docker compose up -d          # loads schema (01), seed (02), and functions (order_repository.sql)
mvn spring-boot:run
docker compose down
```

## The functions are gated by tests

`order_repository.sql` is the Repository implementation (hand-written here; in production
tieto-generator writes it with AI). The acceptance gate is
`src/test/java/.../OrderServiceIntegrationTest.java`: it drives the auto-wired `OrderService` /
`OrderRepository` through the **real Spring context** (tieto auto-configuration, `@Transactional`)
against a Testcontainers PostgreSQL, and — beyond per-method behaviour — proves the point of the
Spring integration: a tieto write inside a `@Transactional` boundary **joins that transaction and is
rolled back with it**. The committed SQL is accepted only if these tests pass. As Testcontainers
tests they run under `verify` (not `test`), so `mvn test` stays green without Docker:

```bash
mvn -pl examples/spring verify   # needs Docker
```

## API

```bash
# Get order by ID
curl -s http://localhost:8080/orders/1 | jq

# Get orders by customer ID
curl -s 'http://localhost:8080/orders?customerId=CUST-001' | jq

# Create a new order
curl -s -X POST http://localhost:8080/orders \
  -H 'Content-Type: application/json' \
  -d '{
    "customerId": "CUST-003",
    "lines": [
      {"productId": "PROD-X", "quantity": 1, "unitPrice": 99.99},
      {"productId": "PROD-Y", "quantity": 2, "unitPrice": 24.50}
    ]
  }'

# Verify the new order
curl -s 'http://localhost:8080/orders?customerId=CUST-003' | jq

# Update order status
curl -s -X PATCH http://localhost:8080/orders/4/status \
  -H 'Content-Type: application/json' \
  -d '{"status": "CONFIRMED"}'

# Verify the update
curl -s http://localhost:8080/orders/4 | jq
```

## Regenerating the functions with AI (optional)

To (re)generate `order_repository.sql` from the interface instead of editing it by hand — the
default `generate` writes the file for review and verifies it against a running database first
(`--verify`, on by default; needs `docker compose up` and CREATE rights):

```bash
cd /path/to/tieto
mvn package -pl tieto-generator -am -DskipTests
tieto-generator/target/tieto generate \
  --source-dir examples/spring/src/main/java \
  --repository net.unit8.tieto.example.domain.OrderRepository \
  --db-url jdbc:postgresql://localhost:5432/tieto_example \
  --db-user tieto --db-password tieto \
  --ai-provider claude-cli \
  --output-dir examples/spring/src/main/resources/db
# review the diff, run `mvn -pl examples/spring verify`, then commit if green
```
