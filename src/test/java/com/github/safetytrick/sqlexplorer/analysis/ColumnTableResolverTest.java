package com.github.safetytrick.sqlexplorer.analysis;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.github.safetytrick.sqlexplorer.analysis.resolver.ColumnTableResolver;
import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.schema.ForeignKey;
import com.github.safetytrick.sqlexplorer.schema.LoadedSchema;
import com.github.safetytrick.sqlexplorer.schema.TableStructure;
import net.sf.jsqlparser.JSQLParserException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ColumnTableResolverTest {

    @Test
    void qualified_schema_and_default() throws JSQLParserException {

        String memberRefCreate = """
            CREATE TABLE `member_ref` (
              `id` varchar(36) NOT NULL,
              `member_id` varchar(36) NOT NULL DEFAULT '0' COMMENT 'The ID of the member owning this card number',
              `loyalty_card_number` varchar(255) DEFAULT NULL COMMENT 'The loyalty card number or master member index number',
              `branch_id` varchar(36) NOT NULL DEFAULT '0' COMMENT 'The branch issuing this card number',
              `created_on` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
              `data_source` varchar(36) NOT NULL,
              `last_seen` datetime NOT NULL,
              `ref_type` varchar(45) DEFAULT NULL,
              PRIMARY KEY (`id`) USING BTREE,
              UNIQUE KEY `unique_ref_fid` (`loyalty_card_number`,`branch_id`),
              KEY `member_ref__member_id_idx` (`member_id`),
              KEY `member_ref__branch_id_idx` (`branch_id`)
            ) ENGINE=InnoDB DEFAULT CHARSET=latin1 COMMENT='A Members Loyalty Card Number. For rows where branch_id';""";

        TableStructure memberRef = TableStructure.fromCreateTable(memberRefCreate).schema("tdb").build();

        String ccdCreate = """
            CREATE TABLE `doc` (
              `id` char(36) NOT NULL,
              `home_community_id` varchar(64) NOT NULL,
              `repository_unique_id` varchar(64) DEFAULT NULL,
              `document_unique_id` varchar(128) NOT NULL,
              `document_name` varchar(256) DEFAULT NULL,
              `author` varchar(128) DEFAULT NULL,
              `author_institution` varchar(256) DEFAULT NULL,
              `mime_type` varchar(64) DEFAULT NULL,
              `service_start_time` datetime DEFAULT NULL,
              `service_end_time` datetime DEFAULT NULL,
              `document_type_code` varchar(45) DEFAULT NULL,
              `date_created` datetime DEFAULT NULL,
              `raw_document_meta` varchar(256) DEFAULT NULL,
              `member_id` char(36) NOT NULL,
              `created_on` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
              `datasource` char(36) NOT NULL,
              `deleted_on` char(36) DEFAULT NULL,
              `deleted_by` char(36) DEFAULT NULL,
              `member_session_id` varchar(36) DEFAULT NULL,
              PRIMARY KEY (`id`),
              KEY `ccd__member_id_idx` (`member_id`,`date_created`),
              KEY `ccd__document_id_idx` (`document_unique_id`),
              KEY `ccd__created_on_idx` (`created_on`),
              KEY `ccd__member_session_id_idx` (`member_session_id`,`date_created`)
            ) ENGINE=InnoDB DEFAULT CHARSET=latin1;""";

        TableStructure doc = TableStructure.fromCreateTable(ccdCreate).schema("doc").build();

        ColumnTableResolver columnTableResolver =
            new ColumnTableResolver(new LoadedSchema(Arrays.asList(memberRef, doc)));

        List<String> filteredNames = Arrays.asList("doc.doc", "member_ref");
        String columnName = "member_session_id";

        String tableName = columnTableResolver.selectTableNameFromCandidates(filteredNames, columnName);
        assertEquals("doc.doc", tableName);

    }

    @Test
    void single_column_from_single_table() throws JSQLParserException {
        String branch_config_create = """
            CREATE TABLE `branch_config` (
              `id` char(36) NOT NULL,
              `branch_id` char(36) NOT NULL,
              `name` varchar(255) NOT NULL,
              `value` text,
              `created_by` char(36) NOT NULL DEFAULT '1',
              `created_on` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
              `deleted_by` char(36) DEFAULT NULL,
              `deleted_on` timestamp NULL DEFAULT NULL,
              PRIMARY KEY (`id`),
              KEY `branch_config__branch_id_name_idx` (`branch_id`,`name`)
            ) ENGINE=InnoDB DEFAULT CHARSET=latin1;""";
        TableStructure branch_config_table = TableStructure.fromCreateTable(branch_config_create).schema("tdb").build();

        ColumnTableResolver columnTableResolver =
            new ColumnTableResolver(new LoadedSchema(Collections.singletonList(branch_config_table)));

        String table = columnTableResolver.selectTableNameFromCandidates(Collections.singletonList("branch_config"), "branch_id");

        assertEquals("branch_config", table);
    }

    @Test
    void foreign_key_parsing() throws JSQLParserException {
        String addressTable = """
            CREATE TABLE address (
              address_id SMALLINT UNSIGNED NOT NULL AUTO_INCREMENT,
              address VARCHAR(50) NOT NULL,
              address2 VARCHAR(50) DEFAULT NULL,
              district VARCHAR(20) NOT NULL,
              city_id SMALLINT UNSIGNED NOT NULL,
              postal_code VARCHAR(10) DEFAULT NULL,
              phone VARCHAR(20) NOT NULL,
              last_update TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              PRIMARY KEY  (address_id),
              KEY idx_fk_city_id (city_id),
              CONSTRAINT `fk_address_city` FOREIGN KEY (city_id) REFERENCES city (city_id) ON DELETE RESTRICT ON UPDATE CASCADE
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """;

        TableStructure address_table = TableStructure.fromCreateTable(addressTable).build();
        assertFalse(address_table.foreignKeys().isEmpty());
        for (ForeignKey foreignKey : address_table.foreignKeys()) {
            ColumnReference from_city_id = foreignKey.from().getFirst();
            ColumnReference to_city_id = foreignKey.to().getFirst();
            assertEquals("address.city_id", from_city_id.toStringNormalized());

            assertEquals("city.city_id", to_city_id.toStringNormalized());
        }
    }

    @Test
    void foreign_key_parsing_with_default_schema() throws JSQLParserException {
        String addressTable = """
            CREATE TABLE address (
              address_id SMALLINT UNSIGNED NOT NULL AUTO_INCREMENT,
              address VARCHAR(50) NOT NULL,
              address2 VARCHAR(50) DEFAULT NULL,
              district VARCHAR(20) NOT NULL,
              city_id SMALLINT UNSIGNED NOT NULL,
              postal_code VARCHAR(10) DEFAULT NULL,
              phone VARCHAR(20) NOT NULL,
              last_update TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              PRIMARY KEY  (address_id),
              KEY idx_fk_city_id (city_id),
              CONSTRAINT `fk_address_city` FOREIGN KEY (city_id) REFERENCES city (city_id) ON DELETE RESTRICT ON UPDATE CASCADE
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """;

        TableStructure address_table = TableStructure.fromCreateTable(addressTable, "some_schema").build();
        assertFalse(address_table.foreignKeys().isEmpty());
        for (ForeignKey foreignKey : address_table.foreignKeys()) {
            ColumnReference from_city_id = foreignKey.from().getFirst();
            ColumnReference to_city_id = foreignKey.to().getFirst();
            assertEquals("some_schema.address.city_id", from_city_id.toStringNormalized());
            assertEquals("some_schema.city.city_id", to_city_id.toStringNormalized());
        }
    }
}
