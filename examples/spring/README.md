# tieto Spring Boot Example

## Start

```bash
docker compose up -d
```

## Generate Functions

Build tieto-generator and generate PostgreSQL Functions from the Repository interface:

```bash
cd /path/to/tieto
mvn package -pl tieto-generator -am -DskipTests
tieto-generator/target/tieto generate \
  --source-dir examples/spring/src/main/java \
  --repository net.unit8.tieto.example.domain.OrderRepository \
  --db-url jdbc:postgresql://localhost:5432/tieto_example \
  --db-user tieto --db-password tieto \
  --ai-provider claude-cli
```

## Run

```bash
mvn spring-boot:run
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

## Stop

```bash
docker compose down
```
