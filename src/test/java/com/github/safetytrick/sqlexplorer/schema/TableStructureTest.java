package com.github.safetytrick.sqlexplorer.schema;

import java.util.Map;
import java.util.function.Function;

import net.sf.jsqlparser.JSQLParserException;
import org.junit.jupiter.api.Test;

import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.assertThat;

class TableStructureTest {

    /**
     * {@code fromCreateTable} captures the numeric type arguments so downstream
     * consumers (e.g. foreign-key type-mismatch checks) can compare
     * {@code INT(10)} against {@code BIGINT(19)}. Non-numeric arguments such as
     * {@code ENUM(...)} values and unspecified sizes fall back to 0.
     */
    @Test
    void fromCreateTable_captures_column_size_and_scale() throws JSQLParserException {
        String create = """
            CREATE TABLE `widget` (
              `id` BIGINT(19) NOT NULL,
              `qty` INT(10) NOT NULL,
              `price` DECIMAL(10,2) DEFAULT NULL,
              `label` VARCHAR(255) DEFAULT NULL,
              `status` ENUM('active','deleted') DEFAULT NULL,
              `note` TEXT,
              PRIMARY KEY (`id`)
            ) ENGINE=InnoDB DEFAULT CHARSET=latin1;""";

        TableStructure widget = TableStructure.fromCreateTable(create, "tdb").build();

        Map<String, TableColumn> byName = widget.columns().stream()
            .collect(toMap(TableColumn::columnName, Function.identity()));

        assertThat(byName.get("id").columnSize()).isEqualTo(19);
        assertThat(byName.get("qty").columnSize()).isEqualTo(10);

        assertThat(byName.get("price").columnSize()).isEqualTo(10);
        assertThat(byName.get("price").decimalDigits()).isEqualTo(2);

        assertThat(byName.get("label").columnSize()).isEqualTo(255);

        // ENUM arguments are not numeric, and TEXT carries no argument at all.
        assertThat(byName.get("status").columnSize()).isZero();
        assertThat(byName.get("note").columnSize()).isZero();

        assertThat(widget.type()).isEqualTo("TABLE");
    }

    @Test
    void fromCreateTable_mirrors_the_jdbc_column_attributes() throws JSQLParserException {
        String create = """
            CREATE TABLE `Widget_Log` (
              `id` BIGINT(19) NOT NULL AUTO_INCREMENT,
              `widget_id` BIGINT(19) NOT NULL,
              `label` VARCHAR(255) DEFAULT 'none',
              `noted_on` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
              `note` TEXT,
              PRIMARY KEY (`id`),
              UNIQUE KEY `widget_log__label_uq` (`label`),
              KEY `widget_log__widget_id_idx` (`widget_id`)
            ) ENGINE=InnoDB;""";

        TableStructure log = TableStructure.fromCreateTable(create, "TDB").build();

        // table/schema names normalize like every other identifier
        assertThat(log.name()).isEqualTo("widget_log");
        assertThat(log.schema()).isEqualTo("tdb");

        Map<String, TableColumn> byName = log.columns().stream()
            .collect(toMap(TableColumn::columnName, Function.identity()));

        assertThat(byName.get("id").isNullable()).isEqualTo("NO");
        assertThat(byName.get("id").isAutoIncrement()).isEqualTo("YES");
        assertThat(byName.get("id").type()).isEqualTo(java.sql.JDBCType.BIGINT);
        assertThat(byName.get("id").ordinal()).isEqualTo(1);

        assertThat(byName.get("label").isNullable()).isEqualTo("YES");
        assertThat(byName.get("label").columnDefault()).isEqualTo("none");
        assertThat(byName.get("label").type()).isEqualTo(java.sql.JDBCType.VARCHAR);

        assertThat(byName.get("noted_on").columnDefault()).isEqualTo("CURRENT_TIMESTAMP");
        assertThat(byName.get("noted_on").type()).isEqualTo(java.sql.JDBCType.TIMESTAMP);

        assertThat(byName.get("note").type()).isEqualTo(java.sql.JDBCType.LONGVARCHAR);
        assertThat(byName.get("note").isNullable()).isEqualTo("YES");
        assertThat(byName.get("note").columnDefault()).isNull();

        // index uniqueness and the JDBC-style PRIMARY name
        assertThat(log.indices())
            .extracting(TableIndex::name, TableIndex::unique)
            .containsExactlyInAnyOrder(
                org.assertj.core.api.Assertions.tuple("PRIMARY", true),
                org.assertj.core.api.Assertions.tuple("widget_log__label_uq", true),
                org.assertj.core.api.Assertions.tuple("widget_log__widget_id_idx", false));
    }

    @Test
    void fromCreateTable_handles_create_view() throws JSQLParserException {
        String create = "CREATE VIEW `active_widgets` AS SELECT `id`, `label` FROM `widget` WHERE `status` = 'active'";

        TableStructure view = TableStructure.fromCreateTable(create, "tdb").build();

        assertThat(view.name()).isEqualTo("active_widgets");
        assertThat(view.schema()).isEqualTo("tdb");
        assertThat(view.type()).isEqualTo("VIEW");
        // columns are not derivable without resolving the view's select
        assertThat(view.columns()).isEmpty();
        assertThat(view.indices()).isEmpty();
        assertThat(view.foreignKeys()).isEmpty();
        // ... but the base tables are
        assertThat(view.underlyingTables())
            .extracting(t -> t.getNormalizedSchemaName() + "." + t.getNormalizedTableName())
            .containsExactly("tdb.widget");
    }
}
