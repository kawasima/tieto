# tieto Vanilla Example

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
  --source-dir examples/vanilla/src/main/java \
  --repository net.unit8.tieto.example.domain.OrderRepository \
  --db-url jdbc:postgresql://localhost:5432/tieto_example \
  --db-user tieto --db-password tieto \
  --ai-provider claude-cli
```

## Run

```bash
mvn exec:java -pl examples/vanilla
```

## Stop

```bash
docker compose down
```
