package net.unit8.tieto.generator.ai;

import net.unit8.tieto.generator.parser.GeneratorException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeneratedSqlValidatorTest {

    private final GeneratedSqlValidator validator = new GeneratedSqlValidator();

    @Test
    void acceptsASingleCreateOrReplaceFunctionWithTheExpectedName() {
        String sql = """
                CREATE OR REPLACE FUNCTION order_repository_find_by_id_v1(p_id bigint)
                RETURNS jsonb LANGUAGE sql AS $$ SELECT to_jsonb(p_id) $$
                """;
        assertThatCode(() -> validator.validate(sql, "order_repository_find_by_id_v1"))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsTheMainFunctionPlusItsSpecToSqlHelper() {
        String sql = """
                CREATE OR REPLACE FUNCTION order_repository_find_by_v1(spec jsonb)
                RETURNS SETOF jsonb LANGUAGE plpgsql AS $body$
                BEGIN RETURN QUERY EXECUTE 'SELECT 1'; END
                $body$;
                CREATE OR REPLACE FUNCTION order_repository_find_by_v1_spec_to_sql(spec jsonb)
                RETURNS text LANGUAGE sql AS $$ SELECT 'TRUE' $$;
                """;
        assertThatCode(() -> validator.validate(sql, "order_repository_find_by_v1"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsProseWithNoFunctionDefinition() {
        String sql = "I'm sorry, I cannot generate that function.";
        assertThatThrownBy(() -> validator.validate(sql, "order_repository_find_by_id_v1"))
                .isInstanceOf(GeneratorException.class);
    }

    @Test
    void rejectsADestructiveStatementAppendedAfterTheFunction() {
        String sql = """
                CREATE OR REPLACE FUNCTION order_repository_find_by_id_v1(p_id bigint)
                RETURNS jsonb LANGUAGE sql AS $$ SELECT to_jsonb(p_id) $$;
                DROP TABLE orders;
                """;
        assertThatThrownBy(() -> validator.validate(sql, "order_repository_find_by_id_v1"))
                .isInstanceOf(GeneratorException.class)
                .hasMessageContaining("DROP TABLE orders".substring(0, 4));
    }

    @Test
    void allowsKeywordsLikeDropInsideTheDollarQuotedBody() {
        // A ';' and the word DROP inside the function body must not be treated
        // as top-level statements.
        String sql = """
                CREATE OR REPLACE FUNCTION order_repository_purge_v1(p_id bigint)
                RETURNS void LANGUAGE plpgsql AS $$
                BEGIN
                    DELETE FROM order_lines WHERE order_id = p_id;
                    DELETE FROM orders WHERE id = p_id;
                END
                $$
                """;
        assertThatCode(() -> validator.validate(sql, "order_repository_purge_v1"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsAFunctionWhoseNameDoesNotMatchTheExpectedName() {
        String sql = """
                CREATE OR REPLACE FUNCTION something_else_v1(p_id bigint)
                RETURNS jsonb LANGUAGE sql AS $$ SELECT to_jsonb(p_id) $$
                """;
        assertThatThrownBy(() -> validator.validate(sql, "order_repository_find_by_id_v1"))
                .isInstanceOf(GeneratorException.class)
                .hasMessageContaining("something_else_v1");
    }

    @Test
    void rejectsWhenTheExpectedMainFunctionIsMissing() {
        // Only the helper is present, not the main function.
        String sql = """
                CREATE OR REPLACE FUNCTION order_repository_find_by_v1_spec_to_sql(spec jsonb)
                RETURNS text LANGUAGE sql AS $$ SELECT 'TRUE' $$
                """;
        assertThatThrownBy(() -> validator.validate(sql, "order_repository_find_by_v1"))
                .isInstanceOf(GeneratorException.class)
                .hasMessageContaining("order_repository_find_by_v1");
    }

    @Test
    void ignoresLeadingCommentsAndIsCaseInsensitive() {
        String sql = """
                -- generated function
                create or replace function order_repository_find_by_id_v1(p_id bigint)
                returns jsonb language sql as $$ select to_jsonb(p_id) $$
                """;
        assertThatCode(() -> validator.validate(sql, "order_repository_find_by_id_v1"))
                .doesNotThrowAnyException();
    }
}
