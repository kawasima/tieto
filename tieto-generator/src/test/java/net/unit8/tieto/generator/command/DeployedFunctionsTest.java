package net.unit8.tieto.generator.command;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeployedFunctionsTest {

    @Test
    void declaredInFile_matchesACreateOrReplaceFunction() {
        String sql = "CREATE OR REPLACE FUNCTION order_repository_find_by_id_v1(p bigint)"
                + " RETURNS jsonb LANGUAGE sql AS $$ SELECT 1 $$;";
        assertThat(DeployedFunctions.declaredInFile(sql, "order_repository_find_by_id_v1")).isTrue();
    }

    @Test
    void declaredInFile_matchesAPlainCreateFunctionAndAQuotedName() {
        assertThat(DeployedFunctions.declaredInFile(
                "CREATE FUNCTION foo() RETURNS int LANGUAGE sql AS $$ SELECT 1 $$", "foo")).isTrue();
        assertThat(DeployedFunctions.declaredInFile(
                "CREATE FUNCTION \"foo\"() RETURNS int LANGUAGE sql AS $$ SELECT 1 $$", "foo")).isTrue();
    }

    @Test
    void declaredInFile_doesNotMatchABareMentionInAComment() {
        assertThat(DeployedFunctions.declaredInFile(
                "-- order_repository_find_by_id_v1 is generated elsewhere\nSELECT 1;",
                "order_repository_find_by_id_v1")).isFalse();
    }

    @Test
    void declaredInFile_doesNotMatchALongerFunctionNameThatContainsThisOne() {
        String sql = "CREATE FUNCTION order_repository_find_by_id_v1_spec_to_sql() RETURNS text"
                + " LANGUAGE sql AS $$ SELECT 'x' $$";
        assertThat(DeployedFunctions.declaredInFile(sql, "order_repository_find_by_id_v1")).isFalse();
    }

    @Test
    void declaredInFile_falseWhenAbsent() {
        assertThat(DeployedFunctions.declaredInFile("SELECT 1;", "foo")).isFalse();
    }
}
