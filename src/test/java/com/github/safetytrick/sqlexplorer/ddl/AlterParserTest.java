package com.github.safetytrick.sqlexplorer.ddl;

import com.github.safetytrick.sqlexplorer.ddl.AlterParser.Clause;
import com.github.safetytrick.sqlexplorer.ddl.AlterParser.Kind;
import com.github.safetytrick.sqlexplorer.ddl.AlterParser.ParsedAlter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AlterParserTest {

    @Test
    void readsEveryClauseOfAnAlter() {
        ParsedAlter parsed = AlterParser.parse("ALTER TABLE `app_main`.`account` "
            + "ADD COLUMN name_u8 VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_ci NULL, "
            + "DROP COLUMN legacy, MODIFY status VARCHAR(40) NOT NULL DEFAULT 'new', "
            + "CHANGE name full_name VARCHAR(255), RENAME COLUMN code TO account_code, "
            + "ADD INDEX idx_name (full_name(100), status), ADD UNIQUE KEY uq_code (account_id, code), "
            + "DROP INDEX idx_old, ADD CONSTRAINT fk_site FOREIGN KEY (site_id) REFERENCES app_main.site (id), "
            + "ALTER COLUMN status SET DEFAULT 'x', ALGORITHM=INPLACE, LOCK=NONE;");

        assertThat(parsed.schema()).isEqualTo("app_main");
        assertThat(parsed.table()).isEqualTo("account");
        assertThat(parsed.clauses()).extracting(Clause::kind).containsExactly(
            Kind.ADD_COLUMN, Kind.DROP_COLUMN, Kind.MODIFY_COLUMN, Kind.CHANGE_COLUMN, Kind.RENAME_COLUMN,
            Kind.ADD_INDEX, Kind.ADD_INDEX, Kind.DROP_INDEX, Kind.ADD_FOREIGN_KEY, Kind.COLUMN_DEFAULT,
            Kind.ALGORITHM, Kind.LOCK);
        Clause add = parsed.clauses().getFirst();
        assertThat(add.column().type()).isEqualTo("VARCHAR");
        assertThat(add.column().length()).isEqualTo(255);
        assertThat(add.column().charset()).isEqualTo("utf8mb4");
        assertThat(add.column().collation()).isEqualTo("utf8mb4_0900_as_ci");
        assertThat(parsed.clauses().get(3).name()).isEqualTo("name");
        assertThat(parsed.clauses().get(3).newName()).isEqualTo("full_name");
        assertThat(parsed.clauses().get(5).index().parts().getFirst().prefix()).isEqualTo(100);
        assertThat(parsed.clauses().get(6).index().unique()).isTrue();
        assertThat(parsed.clauses().get(8).referencedTable()).isEqualTo("app_main.site");
        assertThat(parsed.requested(Kind.ALGORITHM)).isEqualTo("INPLACE");
        assertThat(parsed.requested(Kind.LOCK)).isEqualTo("NONE");
    }

    @Test
    void readsCreateAndDropIndexAndTableOptions() {
        ParsedAlter create = AlterParser.parse("CREATE UNIQUE INDEX uq_email ON users (email) ALGORITHM=INPLACE");
        assertThat(create.table()).isEqualTo("users");
        assertThat(create.clauses()).extracting(Clause::kind).containsExactly(Kind.ADD_INDEX, Kind.ALGORITHM);
        assertThat(create.clauses().getFirst().index().unique()).isTrue();

        ParsedAlter drop = AlterParser.parse("DROP INDEX idx_x ON app_main.site");
        assertThat(drop.clauses()).extracting(Clause::kind).containsExactly(Kind.DROP_INDEX);

        ParsedAlter options = AlterParser.parse("ALTER TABLE site CONVERT TO CHARACTER SET utf8mb4 COLLATE "
            + "utf8mb4_0900_ai_ci, ENGINE=InnoDB, ROW_FORMAT=DYNAMIC, DEFAULT CHARSET=utf8mb4, AUTO_INCREMENT=10, "
            + "COMMENT='sites, by account'");
        assertThat(options.clauses()).extracting(Clause::kind).containsExactly(Kind.CONVERT_CHARSET, Kind.REBUILD,
            Kind.ROW_FORMAT, Kind.TABLE_DEFAULT_CHARSET, Kind.AUTO_INCREMENT, Kind.COMMENT);
        assertThat(options.clauses().getFirst().value()).isEqualTo("utf8mb4");
    }

    @Test
    void keepsWhatItDoesNotRecogniseAndRefusesOtherStatements() {
        ParsedAlter odd = AlterParser.parse("ALTER TABLE site ADD PARTITION (PARTITION p3 VALUES LESS THAN (2030))");
        assertThat(odd.clauses()).extracting(Clause::kind).containsExactly(Kind.UNKNOWN);

        ParsedAlter select = AlterParser.parse("SELECT 1");
        assertThat(select.error()).isNotNull();
        assertThat(select.clauses()).isEmpty();
    }

    @Test
    void reportsAnAlgorithmRunOntoAClauseWithoutAComma() {
        ParsedAlter parsed = AlterParser.parse("ALTER TABLE app_main.account\n  CONVERT TO CHARACTER SET utf8mb4 ALGORITHM=COPY");

        assertThat(parsed.clauses()).extracting(Clause::kind).containsExactly(Kind.CONVERT_CHARSET, Kind.ALGORITHM);
        assertThat(parsed.requested(Kind.ALGORITHM)).isEqualTo("COPY");
        assertThat(parsed.syntaxErrors()).singleElement().asString()
            .startsWith("Missing comma")
            .contains("CONVERT TO CHARACTER SET utf8mb4, ALGORITHM=COPY");
    }

    @Test
    void splitsOptionsRunTogetherOnEitherSideAndChecksTheirValues() {
        ParsedAlter parsed = AlterParser.parse(
            "ALTER TABLE account ALGORITHM=INPLACE ADD COLUMN x INT LOCK=NONE, ALGORITHM=FAST");

        assertThat(parsed.clauses()).extracting(Clause::kind)
            .containsExactly(Kind.ALGORITHM, Kind.ADD_COLUMN, Kind.LOCK, Kind.ALGORITHM);
        assertThat(parsed.syntaxErrors()).hasSize(2);
        assertThat(parsed.syntaxErrors().get(1)).contains("ALGORITHM=FAST");
    }

    @Test
    void aColumnNamedLockIsNotAnOption() {
        ParsedAlter parsed = AlterParser.parse("ALTER TABLE account ADD COLUMN lock INT, ALGORITHM=INSTANT");

        assertThat(parsed.syntaxErrors()).isEmpty();
        assertThat(parsed.clauses()).extracting(Clause::kind).containsExactly(Kind.ADD_COLUMN, Kind.ALGORITHM);
    }
}
