# tieto - Development Instructions

## Project Overview

Repository implementations externalized as PostgreSQL Functions. Java side has only interfaces; persistence logic lives in the database. See README.md for the concept.

## Module Structure

```
tieto-core/       Core library: proxy, JSONB mapping, function invocation
tieto-spring/     Spring Boot integration (@EnableTietoRepositories, @Transactional)
tieto-generator/  CLI: generates PostgreSQL Functions from Repository interfaces via AI
tieto-example/    Working example with Docker PostgreSQL
```

## Build

- Java 21, Maven multi-module
- `mvn install -DskipTests` to build all
- `mvn test` to run all tests
- `mvn -pl tieto-core test` to test a single module

## Running the Example

```bash
cd tieto-example && docker compose up -d && cd ..
mvn dependency:build-classpath -pl tieto-example -Dmdep.outputFile=/tmp/tieto-cp.txt
java -cp "tieto-example/target/classes:$(cat /tmp/tieto-cp.txt)" net.unit8.tieto.example.ExampleApp
cd tieto-example && docker compose down
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
- PostgreSQL function names: `{repository_name}_{method_name}` in snake_case
  - `OrderRepository.findById()` → `order_repository_find_by_id`

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
- Domain models and Repository interfaces have **zero dependency on tieto**
- Transactions are externally controlled (TransactionContext for standalone, DataSourceUtils for Spring)
- Method metadata is cached in `ConcurrentHashMap` per proxy instance
