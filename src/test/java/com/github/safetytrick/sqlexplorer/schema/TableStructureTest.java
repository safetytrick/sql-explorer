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
    }
}
