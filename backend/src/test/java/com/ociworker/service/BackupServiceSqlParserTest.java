package com.ociworker.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class BackupServiceSqlParserTest {

    @Test
    void keepsSemicolonsAndNewlinesInsideScriptLiterals() {
        String sql = "-- header\n"
                + "INSERT INTO `oci_webssh_script_bookmark` (name, command_encrypted) VALUES "
                + "('demo', 'line one;\\nline two;\\nline three');\n"
                + "DELETE FROM `oci_webssh_connection_bookmark`;\n";

        List<String> statements = BackupService.splitSqlStatements(sql);

        assertThat(statements).hasSize(2);
        assertThat(statements.get(0)).contains("line one;\\nline two;\\nline three");
        assertThat(statements.get(1)).isEqualTo("DELETE FROM `oci_webssh_connection_bookmark`");
    }

    @Test
    void ignoresSemicolonsInsideCommentsAndSupportsQuotedQuotes() {
        String sql = "/* comment; */ SELECT 'it\\'s; safe'; -- tail;\n"
                + "SELECT \"double; safe\";";

        assertThat(BackupService.splitSqlStatements(sql)).containsExactly(
                "SELECT 'it\\'s; safe'", "SELECT \"double; safe\"");
    }

    @Test
    void escapesControlCharactersWithoutEmbeddingRawNewlines() {
        String value = "a\\b'c\n\r\t\u0000\u001a";

        assertThat(BackupService.escapeSqlString(value))
                .isEqualTo("a\\\\b\\'c\\n\\r\\t\\0\\Z");
    }

    @Test
    void includesBookmarkTablesAndRecognizesQuotedMutations() throws Exception {
        Field field = BackupService.class.getDeclaredField("TABLES");
        field.setAccessible(true);
        String[] tables = (String[]) field.get(null);

        assertThat(tables).contains("oci_webssh_connection_bookmark", "oci_webssh_script_bookmark");
        assertThat(BackupService.containsTableMutation(
                "DELETE FROM `oci_webssh_script_bookmark`;", "oci_webssh_script_bookmark")).isTrue();
        assertThat(BackupService.containsTableMutation(
                "-- Table: oci_webssh_script_bookmark\n", "oci_webssh_script_bookmark")).isFalse();
    }
}
