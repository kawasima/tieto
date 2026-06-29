# tieto

[![CI](https://github.com/kawasima/tieto/actions/workflows/ci.yml/badge.svg)](https://github.com/kawasima/tieto/actions/workflows/ci.yml)

## Free your domain models from the gravity of database design

Domain models bend under the gravitational pull of database schema. Even with ORMs, table structures bleed into domain objects until models become mere mirrors of tables.

One root cause: persistence and domain logic live side by side in the same codebase.

**tieto** takes a different approach — Repository *implementations* are externalized entirely as PostgreSQL Functions/Procedures. Your Java code contains only the interface. The database holds the implementation. And generative AI writes the plpgsql you'd rather not.

```text
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
                │ SELECT * FROM order_repository_find_by_id_v1(?)
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

- **Minimal dependency in domain models** — only `@FunctionVersion` annotation (optional) from tieto-core. Your domain models themselves know nothing about tieto.
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

### 2. Define a Repository interface

Write query/update specs as Javadoc in natural language. This becomes the prompt for AI generation. Use `@FunctionVersion` to version each function.

```java
import net.unit8.tieto.core.annotation.FunctionVersion;

public interface OrderRepository {

    /** Join orders with order_lines and return the aggregate as nested JSON. */
    @FunctionVersion(1)
    Optional<Order> findById(Long id);

    /** Find all orders for a customer, ordered by creation date descending. */
    @FunctionVersion(1)
    List<Order> findByCustomerId(String customerId);

    /** Insert into orders and order_lines. Auto-generate the order ID. */
    @FunctionVersion(1)
    void save(Order order);
}
```

`@FunctionVersion` is optional — defaults to v1 if omitted. Bump the version number when you change the function spec, and tieto-generator will generate a new version while the old one remains deployed.

### 3. Generate PostgreSQL Functions with tieto-generator

```bash
# Using CLI (e.g. claude CLI) — no API key needed, deploys directly to DB
tieto generate \
  --source-dir src/main/java \
  --repository net.unit8.tieto.example.domain.OrderRepository \
  --db-url jdbc:postgresql://localhost:5432/tieto_example \
  --db-user tieto --db-password tieto \
  --ai-provider claude-cli

# Using a custom CLI command
tieto generate ... --ai-command "ollama run codellama"

# Output to file instead of deploying directly
tieto generate ... --output-mode file

# Using API directly
tieto generate ... --ai-provider claude --ai-api-key $ANTHROPIC_API_KEY
```

Secrets — the DB password and the AI API key — are better supplied as environment
variables (`TIETO_DB_PASSWORD`, `TIETO_AI_API_KEY`) than on the command line, where
they would land in the process list, shell history, and CI logs. You can also pass
`--db-password` / `--ai-api-key` with no value to be prompted (no echo):

```bash
export TIETO_DB_PASSWORD=...   # picked up automatically; nothing on argv
tieto generate ... --db-user tieto --ai-provider claude   # prompts for the key, or reads TIETO_AI_API_KEY
```

The AI reads the Repository interface Javadoc + the live database schema and produces PostgreSQL Functions. By default, functions are deployed directly to the database. Use `--output-mode file` to write SQL files instead.

If a function version already exists in the database, it is skipped. Use `--force` to regenerate.

#### Verifying generated functions

The generator does not deploy AI output blindly. In `deploy` mode every function is created and verified inside a single transaction, and the deploy is committed only if every check passes — any failure rolls the whole batch back, so the database is never left with an unverified function:

- **Signature check** — the function's SETOF-ness (a `List<T>` method must be `RETURNS SETOF`), void-ness (a `void` method must be `RETURNS VOID`), and argument count must match the Java method. These are the parts unambiguous from the method shape, so the check never false-rejects a correct function; the exact return base type and per-argument types are left to the behavioral checks below, since the generator and tieto-core classify enums, generics, and scalars differently.
- **Read smoke** — read methods with simple arguments are called once with synthesized values. PostgreSQL resolves a plpgsql body's table/column references only on first call, so this surfaces a body that references a missing column or wrong table — errors that survive `CREATE`. Only a broken body (a SQLSTATE class-42 error) fails the deploy; an error from the synthesized value itself (no row for `INTO STRICT`, a value that isn't a valid enum/number) is tolerated. Writes and methods taking a domain/Specification argument are not smoked (synthetic data cannot be guaranteed to satisfy constraints).
- **Injection probe** — Specification functions are called with a single-quote leaf value to prove leaf values are bound, not concatenated.

For domain-level verification — does the Repository behave correctly with real domain objects? — generate a round-trip test:

```bash
tieto generate ... --emit-test
```

This emits a JUnit + Testcontainers test (`<Repo>RoundTripTest.java`) plus the functions as a test resource. In `deploy` mode the resource is read back complete from the database (`pg_get_functiondef`), so it includes every repository function regardless of how many were regenerated this run. The test drives the Repository through the real tieto-core proxy against a PostgreSQL container loaded with your schema and seed data. Finders with simple arguments get an automatic smoke assertion; a `save` paired with a finder that reads back by one of the saved object's fields gets an automatic round-trip assertion; everything else is a `@Disabled` scaffold with a `// TODO` to fill in (building a valid aggregate that satisfies foreign keys is left to you). The test source is not overwritten on regeneration unless `--force` (so your edits survive), and `--emit-test` works even when all functions already exist. Customize the layout with `--test-output-dir`, `--test-resources-dir`, `--schema-sql`, and `--seed-sql`.

The `tieto` command is built as a [Really Executable JAR](https://picocli.info/#_really_executable_jar):

```bash
mvn package -pl tieto-generator -am -DskipTests
cp tieto-generator/target/tieto /usr/local/bin/
```

### 4. Use the Repository from your application

**Standalone (plain Java):**

```java
TietoClient tieto = TietoClient.builder(dataSource).build();
OrderRepository repo = tieto.createRepository(OrderRepository.class);

Optional<Order> order = repo.findById(1L);
```

**Spring Boot:**

Mark each repository interface with `@TietoRepository` so package scanning registers
it (and only it — unrelated interfaces in the same package are left alone). **Upgrading:**
scanning now requires this marker, so add `@TietoRepository` to every repository
interface you previously relied on being auto-registered.

```java
import net.unit8.tieto.core.annotation.TietoRepository;

@TietoRepository
public interface OrderRepository { ... }
```

```java
@SpringBootApplication
@EnableTietoRepositories("com.example.domain")
public class MyApp { ... }

@Service
public class OrderService {
    private final OrderRepository orderRepository; // auto-wired

    @Transactional
    public void placeOrder(Order order) {
        orderRepository.save(order);
    }
}
```

`@EnableTietoRepositories` accepts `value`/`basePackages` (string packages) or
`basePackageClasses` (type-safe); with no package given it scans the annotated
class's own package. Alternatively, skip the annotation and list packages in
configuration:

```yaml
tieto:
  base-packages: com.example.domain
```

`createRepository()` (standalone) or scanning (Spring) creates a JDK Dynamic Proxy. Each method call translates to a PostgreSQL function invocation like `SELECT * FROM order_repository_find_by_id_v1(?)`. Repositories registered explicitly via `createRepository()` do not need `@TietoRepository`.

## Composable Specifications

Query conditions can be modeled as a composable Specification — a domain model in its own right. Define a sealed hierarchy of `And`/`Or`/`Not` composites plus domain-named leaf predicates, then pass the tree as a Repository argument:

```java
public sealed interface OrderSpec
    permits OrderSpec.And, OrderSpec.Or, OrderSpec.Not,
            OrderSpec.ForCustomer, OrderSpec.HasStatus,
            OrderSpec.CreatedAfter, OrderSpec.HighValue {

    record And(List<OrderSpec> specs) implements OrderSpec {}
    record Or(List<OrderSpec> specs)  implements OrderSpec {}
    record Not(OrderSpec spec)        implements OrderSpec {}

    record ForCustomer(String customerId)   implements OrderSpec {}
    record HasStatus(OrderStatus status)    implements OrderSpec {}
    record CreatedAfter(LocalDateTime t)    implements OrderSpec {}
    record HighValue(BigDecimal min)        implements OrderSpec {}
}
```

```java
// Find high-value orders for CUST-001 that are not still pending
List<Order> orders = repo.findBy(new OrderSpec.And(List.of(
    new OrderSpec.ForCustomer("CUST-001"),
    new OrderSpec.HighValue(new BigDecimal("1000")),
    new OrderSpec.Not(new OrderSpec.HasStatus(OrderStatus.PENDING))
)));
```

The leaves carry no SQL. Unlike a Rails named scope — where the developer writes the `WHERE` fragment — tieto leaves the condition to the AI: given the leaf's name, its fields, and the schema, the generator produces the SQL. For example, with no `total` column in the schema, `HighValue` is mapped to `SUM(quantity * unit_price)` over the order lines.

The Specification tree travels to PostgreSQL as a single JSONB argument. Each node carries a `"kind"` discriminator (the camelCase simple class name); the remaining keys are the record components:

```json
{ "kind": "and", "specs": [
  { "kind": "forCustomer", "customerId": "CUST-001" },
  { "kind": "highValue", "min": 1000 }
]}
```

This `"kind"` tag is added by convention during serialization — the Specification records themselves stay free of any annotation. tieto-generator emits a recursive function (`<function>_spec_to_sql`) that walks the tree into a `WHERE` clause, so arbitrarily nested `And`/`Or`/`Not` compositions work. See `examples/vanilla` for a runnable end-to-end example.

## Modules

| Module | Role |
|--------|------|
| **tieto-core** | Repository proxy, JSONB mapping, function invocation |
| **tieto-spring** | Spring Boot integration (`@EnableTietoRepositories`, `@Transactional` support) |
| **tieto-generator** | CLI that generates PostgreSQL Functions from Repository interfaces + DB schema |

## Examples

| Directory | Description |
|-----------|-------------|
| `examples/vanilla/` | Plain Java — no framework |
| `examples/spring/` | Spring Boot — `@Transactional`, auto-wired repositories |

## Build and run

```bash
# Build the library and the tieto CLI
mvn install -DskipTests
mvn package -pl tieto-generator -am -DskipTests

# Run the vanilla example
cd examples/vanilla && docker compose up -d && cd ../..
# Generate Functions (schema + test data are loaded by Docker automatically)
tieto-generator/target/tieto generate \
  --source-dir examples/vanilla/src/main/java \
  --repository net.unit8.tieto.example.domain.OrderRepository \
  --db-url jdbc:postgresql://localhost:5432/tieto_example \
  --db-user tieto --db-password tieto \
  --ai-provider claude-cli
mvn exec:java -pl examples/vanilla
cd examples/vanilla && docker compose down && cd ../..

# Run the Spring Boot example
cd examples/spring && docker compose up -d && cd ../..
tieto-generator/target/tieto generate \
  --source-dir examples/spring/src/main/java \
  --repository net.unit8.tieto.example.domain.OrderRepository \
  --db-url jdbc:postgresql://localhost:5432/tieto_example \
  --db-user tieto --db-password tieto \
  --ai-provider claude-cli
cd examples/spring && mvn spring-boot:run
docker compose down && cd ../..
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
