# tieto

## Free your domain models from the gravity of database design

Domain models bend under the gravitational pull of database schema. Even with ORMs, table structures bleed into domain objects until models become mere mirrors of tables.

One root cause: persistence and domain logic live side by side in the same codebase.

**tieto** takes a different approach — Repository *implementations* are externalized entirely as PostgreSQL Functions/Procedures. Your Java code contains only the interface. The database holds the implementation. And generative AI writes the plpgsql you'd rather not.

```
┌─────────────────────────────────┐
│  Application                    │
│                                 │
│  ┌───────────────────────────┐  │
│  │ Domain Layer              │  │
│  │  Order, OrderLine, ...   │  │  ← Pure domain models
│  │  OrderRepository (IF)     │  │  ← Interface only
│  └───────────────────────────┘  │
│               │                 │
│  ┌────────────┴──────────────┐  │
│  │ tieto-core (Proxy)        │  │  ← JSONB conversion + function call
│  └────────────┬──────────────┘  │
└───────────────┼─────────────────┘
                │ SELECT * FROM order_repository_find_by_id(?)
                ▼
┌─────────────────────────────────┐
│  PostgreSQL                     │
│  ┌───────────────────────────┐  │
│  │ Functions (Repository impl)│  │  ← Written by AI
│  │  order_repository_find_... │  │
│  │  order_repository_save ... │  │
│  └───────────────────────────┘  │
│  ┌───────────────────────────┐  │
│  │ Tables (DDL)              │  │  ← Managed by humans
│  │  orders, order_lines, ... │  │
│  └───────────────────────────┘  │
└─────────────────────────────────┘
```

### Key ideas

- **Zero framework dependency in domain models** — records, classes, whatever you like. Your domain knows nothing about tieto.
- **Repositories are interfaces only** — no implementation code on the Java side.
- **AI writes the Functions** — plpgsql is tedious for humans but well-suited for generative AI given a schema and a spec.
- **Plain JDBC, no ORM** — domain objects travel as JSONB between Java and PostgreSQL.

## Usage

### 1. Define your domain model (no tieto dependency)

```java
public record Order(
    Long id,
    String customerId,
    List<OrderLine> lines,
    OrderStatus status,
    LocalDateTime createdAt
) {}
```

### 2. Define a Repository interface (no tieto dependency)

Write query/update specs as Javadoc in natural language. This becomes the prompt for AI generation.

```java
public interface OrderRepository {

    /** Join orders with order_lines and return the aggregate as nested JSON. */
    Optional<Order> findById(Long id);

    /** Find all orders for a customer, ordered by creation date descending. */
    List<Order> findByCustomerId(String customerId);

    /** Insert into orders and order_lines. Auto-generate the order ID. */
    void save(Order order);
}
```

### 3. Generate PostgreSQL Functions with tieto-generator

```bash
tieto generate \
  --source-dir src/main/java \
  --repository com.example.domain.OrderRepository \
  --db-url jdbc:postgresql://localhost:5432/mydb \
  --db-user postgres --db-password secret \
  --ai-provider claude --ai-api-key $ANTHROPIC_API_KEY \
  --output-mode file
```

The AI reads the Repository interface Javadoc + the live database schema and produces PostgreSQL Functions.

### 4. Use the Repository from your application

```java
TietoClient tieto = TietoClient.builder(dataSource).build();
OrderRepository repo = tieto.createRepository(OrderRepository.class);

Optional<Order> order = repo.findById(1L);
```

`createRepository()` returns a JDK Dynamic Proxy. Each method call translates to a PostgreSQL function invocation like `SELECT * FROM order_repository_find_by_id(?)`.

## Modules

| Module | Role |
|--------|------|
| **tieto-core** | Repository proxy, JSONB mapping, function invocation |
| **tieto-spring** | Spring Boot integration (`@EnableTietoRepositories`, `@Transactional` support) |
| **tieto-generator** | CLI that generates PostgreSQL Functions from Repository interfaces + DB schema |
| **tieto-example** | Working example application |

## Build and run

```bash
# Build
mvn install -DskipTests

# Run the example
cd tieto-example
docker compose up -d         # Start PostgreSQL
cd ..
mvn dependency:build-classpath -pl tieto-example -Dmdep.outputFile=/tmp/tieto-cp.txt
java -cp "tieto-example/target/classes:$(cat /tmp/tieto-cp.txt)" \
  net.unit8.tieto.example.ExampleApp

# Cleanup
cd tieto-example && docker compose down
```

## Why Functions/Procedures?

- **Domain model freedom** — no 1:1 table mapping constraint. Aggregates travel as JSONB.
- **Physical separation of persistence** — not a single `INSERT INTO` in your Java code.
- **Optimization stays in the database** — JOINs, indexes, and query plans are encapsulated inside Functions.
- **Great fit for generative AI** — plpgsql follows repetitive patterns; given schema information, AI generates it accurately.

## Requirements

- Java 21+
- PostgreSQL 16+
- Maven 3.9+
