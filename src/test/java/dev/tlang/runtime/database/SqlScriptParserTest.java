package dev.tlang.runtime.database;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class SqlScriptParserTest {
    @Test
    void preservesSemicolonsInStringsIdentifiersAndComments() {
        String sql = """
            -- comment ; ignored
            CREATE TABLE "semi;table" (value TEXT);
            INSERT INTO "semi;table" VALUES ('hello; world');
            /* block ; comment */ SELECT `semi;column` FROM [semi;table];
            """;
        List<String> statements = SqlScriptParser.split(sql, SqlScriptParser.Dialect.SQLITE);
        assertEquals(3, statements.size());
        assertTrue(statements.get(1).contains("'hello; world'"));
    }

    @Test
    void preservesPostgresDollarQuotesAndEscapeStrings() {
        String sql = """
            CREATE FUNCTION answer() RETURNS integer AS $body$
            BEGIN
              PERFORM 'inside;body';
              RETURN 42;
            END;
            $body$ LANGUAGE plpgsql;
            INSERT INTO audit(value) VALUES (E'escaped\\';semicolon');
            """;
        List<String> statements = SqlScriptParser.split(sql, SqlScriptParser.Dialect.POSTGRESQL);
        assertEquals(2, statements.size());
        assertTrue(statements.getFirst().contains("RETURN 42;"));
    }

    @Test
    void preservesCompleteSqliteTriggerProgramsIncludingCaseExpressions() {
        String sql = """
            CREATE TRIGGER audit_insert AFTER INSERT ON source
            BEGIN
              INSERT INTO audit(value) VALUES (
                CASE WHEN new.value = 'x;y' THEN 'yes;value' ELSE 'no' END
              );
              UPDATE counters SET value = value + 1;
            END;
            INSERT INTO source(value) VALUES ('x;y');
            """;
        List<String> statements = SqlScriptParser.split(sql, SqlScriptParser.Dialect.SQLITE);
        assertEquals(2, statements.size());
        assertTrue(statements.getFirst().contains("UPDATE counters"));
    }

    @Test
    void rejectsUnterminatedConstructsAndDoesNotTreatCommentsAsSql() {
        assertTrue(SqlScriptParser.split("-- only;\n/* comments; */",
            SqlScriptParser.Dialect.SQLITE).isEmpty());
        assertThrows(DatabaseFailure.class, () -> SqlScriptParser.split("SELECT 'unterminated",
            SqlScriptParser.Dialect.SQLITE));
        assertThrows(DatabaseFailure.class, () -> SqlScriptParser.split("SELECT $x$unterminated",
            SqlScriptParser.Dialect.POSTGRESQL));
        assertThrows(DatabaseFailure.class, () -> SqlScriptParser.split("/* unterminated",
            SqlScriptParser.Dialect.POSTGRESQL));
    }

    @Test
    void retainsTrailingSqlWithoutASemicolon() {
        List<String> statements = SqlScriptParser.split(
            "CREATE TABLE first(id INTEGER); INSERT INTO first VALUES (1)",
            SqlScriptParser.Dialect.SQLITE);
        assertEquals(2, statements.size());
        assertEquals("INSERT INTO first VALUES (1)", statements.get(1));
    }

    @Test
    void identifiesTopLevelTransactionControlWithoutInspectingCommentsOrBodies() {
        assertTrue(SqlScriptParser.isTransactionControl("/* comment */ COMMIT"));
        assertTrue(SqlScriptParser.isTransactionControl("-- comment\nROLLBACK"));
        assertFalse(SqlScriptParser.isTransactionControl(
            "CREATE FUNCTION f() RETURNS void AS $$ BEGIN COMMIT; END $$ LANGUAGE plpgsql"));
    }
}
