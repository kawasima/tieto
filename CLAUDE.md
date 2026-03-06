# tieto - Development Instructions

## Project Overview

Repository implementations externalized as PostgreSQL Functions. Java side has only interfaces; persistence logic lives in the database. See README.md for the concept.

## Module Structure

```
tieto-core/         Core library: proxy, JSONB mapping, function invocation
tieto-spring/       Spring Boot integration (@EnableTietoRepositories, @Transactional)
tieto-generator/    CLI: generates PostgreSQL Functions from Repository interfaces via AI
examples/vanilla/   Standalone example (plain Java, no framework)
examples/spring/    Spring Boot example (@Transactional, auto-wired repositories)
```

Examples are standalone Maven projects (not submodules of the parent).

## Build

- Java 21, Maven multi-module
- `mvn install -DskipTests` to build all
- `mvn test` to run all tests
- `mvn -pl tieto-core test` to test a single module

## Running the Examples

Docker starts PostgreSQL with schema + test data. Functions are NOT pre-installed — use tieto-generator to deploy them.

```bash
# Build tieto CLI
mvn install -DskipTests
mvn package -pl tieto-generator -am -DskipTests

# Vanilla (plain Java)
cd examples/vanilla && docker compose up -d && cd ../..
tieto-generator/target/tieto generate \
  --source-dir examples/vanilla/src/main/java \
  --repository net.unit8.tieto.example.domain.OrderRepository \
  --db-url jdbc:postgresql://localhost:5432/tieto_example \
  --db-user tieto --db-password tieto \
  --ai-provider claude-cli
mvn exec:java -pl examples/vanilla
cd examples/vanilla && docker compose down && cd ../..

# Spring Boot
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

Docker PostgreSQL: `localhost:5432`, db=`tieto_example`, user=`tieto`, password=`tieto`

## Code Conventions

### Style
- **Records** for all immutable data (domain models, specs, metadata)
- **Sealed interfaces** for type-safe variants (see `ReturnTypeHandler`)
- **`final` classes** for core implementations — prevent unintended extension
- No Lombok. No annotation-heavy frameworks in core.

### Naming
- Package: `net.unit8.tieto.{module}`
- PostgreSQL function names: `{repository_name}_{method_name}_v{N}` in snake_case
  - `OrderRepository.findById()` (v1) → `order_repository_find_by_id_v1`

### JSONB Mapping
- Jackson `ObjectMapper` with `JavaTimeModule`, `FAIL_ON_UNKNOWN_PROPERTIES=false`, dates as ISO strings
- Domain objects bound as JSONB via `PGobject(type="jsonb")`
- Simple types (primitives, String, UUID, java.time, enums) bound directly as SQL parameters
- Convention-based auto-mapping by default; explicit `DomainMapper<T>` only for exceptions

### Function Invocation
- `SELECT * FROM function_name(?, ...)` — not CallableStatement
- Return types: `RETURNS JSONB` for single/Optional, `RETURNS SETOF JSONB` for List, `RETURNS VOID` for void

### Testing
- JUnit 5 + AssertJ
- Testcontainers for PostgreSQL integration tests
- Test-local records/interfaces defined inside test classes

### Key Design Decisions
- Domain models have **zero dependency on tieto**; Repository interfaces depend only on `@FunctionVersion` (optional)
- Transactions are externally controlled (TransactionContext for standalone, DataSourceUtils for Spring)
- Method metadata is cached in `ConcurrentHashMap` per proxy instance
