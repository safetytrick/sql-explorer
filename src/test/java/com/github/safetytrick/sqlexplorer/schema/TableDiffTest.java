package com.github.safetytrick.sqlexplorer.schema;

import com.github.safetytrick.sqlexplorer.schema.TableDiff.Difference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** How two definitions of one table differ, element by element (sqlx-a6z). */
class TableDiffTest {

    private static TableStructure table(String ddl) throws Exception {
        return TableStructure.fromCreateTable(ddl, "app_main").build();
    }

    @Test
    void identicalDefinitionsHaveNoDifferences() throws Exception {
        String ddl = "CREATE TABLE account (id BIGINT NOT NULL, name VARCHAR(64), PRIMARY KEY (id))";

        assertThat(TableDiff.between(table(ddl), table(ddl))).isEmpty();
    }

    @Test
    void aColumnAddedOnOneSideIsNamedWithItsDefinition() throws Exception {
        assertThat(TableDiff.between(
            table("CREATE TABLE account (id BIGINT NOT NULL, PRIMARY KEY (id))"),
            table("CREATE TABLE account (id BIGINT NOT NULL, site_id BIGINT, PRIMARY KEY (id))")))
            .containsExactly(new Difference("column", "site_id", null, "bigint NULL · position 2"));
    }

    @Test
    void aColumnWidenedOnOneSideShowsBothDefinitions() throws Exception {
        assertThat(TableDiff.between(
            table("CREATE TABLE account (id BIGINT NOT NULL, name VARCHAR(32) NOT NULL, PRIMARY KEY (id))"),
            table("CREATE TABLE account (id BIGINT NOT NULL, name VARCHAR(64) NOT NULL, PRIMARY KEY (id))")))
            .containsExactly(new Difference("column", "name",
                "varchar(32) NOT NULL · position 2", "varchar(64) NOT NULL · position 2"));
    }

    @Test
    void anIndexMissingOnOneSideIsNamed() throws Exception {
        assertThat(TableDiff.between(
            table("CREATE TABLE account (id BIGINT NOT NULL, name VARCHAR(32), KEY idx_name (name), PRIMARY KEY (id))"),
            table("CREATE TABLE account (id BIGINT NOT NULL, name VARCHAR(32), PRIMARY KEY (id))")))
            .containsExactly(new Difference("index", "idx_name", "INDEX (name)", null));
    }

    @Test
    void namesCompareCaseInsensitivelyAsMysqlDoes() throws Exception {
        assertThat(TableDiff.between(
            table("CREATE TABLE account (ID BIGINT NOT NULL, PRIMARY KEY (ID))"),
            table("CREATE TABLE account (id BIGINT NOT NULL, PRIMARY KEY (id))"))).isEmpty();
    }
}
