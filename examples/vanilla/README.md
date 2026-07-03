# tieto Vanilla Example

Plain Java (no framework). The Repository functions are **committed** as
`src/main/resources/db/order_repository.sql` and loaded into PostgreSQL at startup by
docker-compose (it mounts `src/main/resources/db` into the container's init directory), so you can
run the example without any AI generation step.

## Run

```bash
docker compose up -d          # loads schema (01), seed (02), and functions (03)
mvn exec:java -pl examples/vanilla
docker compose down
```

## The functions are gated by tests

`order_repository.sql` is the Repository implementation. In production tieto-generator writes it with
AI from the interface Javadoc + schema; here it is hand-written to keep the example self-contained.
Either way, the acceptance gate is the **hand-written Repository tests** in
`src/test/java/.../OrderRepositoryTest.java`: they drive `OrderRepository` through the real tieto
proxy against a Testcontainers PostgreSQL loaded with the same schema + functions + seed, and assert
what each method must do (including the composable `findBy(OrderSpec)` queries). The committed SQL is
accepted only if they pass.

```bash
mvn -pl examples/vanilla test   # needs Docker
```

This is the recommended lifecycle: write the tests, generate (or hand-write) the SQL, and commit it
only once the tests are green.

## Regenerating the functions with AI (optional)

To (re)generate `order_repository.sql` from the interface instead of editing it by hand — the
default `generate` writes the file for review and, before writing, verifies it against a running
database (`--verify`, on by default; needs `docker compose up` and CREATE rights):

```bash
cd /path/to/tieto
mvn package -pl tieto-generator -am -DskipTests
tieto-generator/target/tieto generate \
  --source-dir examples/vanilla/src/main/java \
  --repository net.unit8.tieto.example.domain.OrderRepository \
  --db-url jdbc:postgresql://localhost:5432/tieto_example \
  --db-user tieto --db-password tieto \
  --ai-provider claude-cli \
  --output-dir examples/vanilla/src/main/resources/db
# review the diff, run `mvn -pl examples/vanilla test`, then commit if green
```
