package com.github.safetytrick.sqlexplorer.analysis;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.github.safetytrick.sqlexplorer.analysis.resolver.ColumnTableResolver;
import com.github.safetytrick.sqlexplorer.model.ColumnReference;
import com.github.safetytrick.sqlexplorer.model.TableReference;
import com.github.safetytrick.sqlexplorer.schema.ForeignKey;
import com.github.safetytrick.sqlexplorer.schema.SchemaLoadingService;
import com.github.safetytrick.sqlexplorer.schema.SqlMetadataService;
import com.github.safetytrick.sqlexplorer.schema.TableStructure;
import net.sf.jsqlparser.JSQLParserException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ColumnTableResolverTest {

    SchemaLoadingService schemaLoadingService = mock(SchemaLoadingService.class);
    SqlMetadataService sqlMetadataService = new SqlMetadataService(schemaLoadingService);
    ColumnTableResolver columnTableResolver = new ColumnTableResolver(sqlMetadataService);

    @Test
    void qualified_schema_and_default() throws JSQLParserException {

        String customerRefCreate = """
            CREATE TABLE `customer_ref` (
              `id` varchar(36) NOT NULL,
              `customer_id` varchar(36) NOT NULL DEFAULT '0' COMMENT 'The ID of the customer to whom this REF belongs',
              `reference_number` varchar(255) DEFAULT NULL COMMENT 'The reference number of master customer index number',
              `store_id` varchar(36) NOT NULL DEFAULT '0' COMMENT 'The store owning this REF',
              `created_on` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
              `data_source` varchar(36) NOT NULL,
              `last_seen` datetime NOT NULL,
              `ref_type` varchar(45) DEFAULT NULL,
              PRIMARY KEY (`id`) USING BTREE,
              UNIQUE KEY `unique_ref_fid` (`reference_number`,`store_id`),
              KEY `customer_ref__customer_id_idx` (`customer_id`),
              KEY `customer_ref__store_id_idx` (`store_id`)
            ) ENGINE=InnoDB DEFAULT CHARSET=latin1 COMMENT='A Customers Reference Number. For rows where store_id';""";

        TableStructure customerRef = TableStructure.fromCreateTable(customerRefCreate).schema("tdb").build();

        String ccdCreate = """
            CREATE TABLE `ccd` (
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
              `customer_id` char(36) NOT NULL,
              `created_on` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
              `datasource` char(36) NOT NULL,
              `deleted_on` char(36) DEFAULT NULL,
              `deleted_by` char(36) DEFAULT NULL,
              `customer_visit_id` varchar(36) DEFAULT NULL,
              PRIMARY KEY (`id`),
              KEY `ccd__customer_id_idx` (`customer_id`,`date_created`),
              KEY `ccd__document_id_idx` (`document_unique_id`),
              KEY `ccd__created_on_idx` (`created_on`),
              KEY `ccd__customer_visit_id_idx` (`customer_visit_id`,`date_created`)
            ) ENGINE=InnoDB DEFAULT CHARSET=latin1;""";

        TableStructure ccd = TableStructure.fromCreateTable(ccdCreate).schema("ccd").build();

        when(schemaLoadingService.getAllByTableName("ccd")).thenReturn(Collections.singletonList(ccd));
        when(schemaLoadingService.getAllByTableName("customer_ref")).thenReturn(Collections.singletonList(customerRef));

        when(sqlMetadataService.getTable(new TableReference("ccd", "ccd"))).thenReturn(ccd);
        when(sqlMetadataService.getTable(new TableReference("tdb", "customer_ref"))).thenReturn(customerRef);

        List<String> filteredNames = Arrays.asList("ccd.ccd", "customer_ref");
        String columnName = "customer_visit_id";

        String tableName = columnTableResolver.selectTableNameFromCandidates(filteredNames, columnName);
        assertEquals("ccd.ccd", tableName);

    }

    @Test
    void single_column_from_single_table() throws JSQLParserException {
        String store_config_create = """
            CREATE TABLE `store_config` (
              `id` char(36) NOT NULL,
              `store_id` char(36) NOT NULL,
              `name` varchar(255) NOT NULL,
              `value` text,
              `created_by` char(36) NOT NULL DEFAULT '1',
              `created_on` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
              `deleted_by` char(36) DEFAULT NULL,
              `deleted_on` timestamp NULL DEFAULT NULL,
              PRIMARY KEY (`id`),
              KEY `store_config__store_id_name_idx` (`store_id`,`name`)
            ) ENGINE=InnoDB DEFAULT CHARSET=latin1;""";
        TableStructure store_config_table = TableStructure.fromCreateTable(store_config_create).schema("tdb").build();

        when(schemaLoadingService.getAllByTableName("store_config")).thenReturn(Collections.singletonList(store_config_table));
        when(sqlMetadataService.getTable(new TableReference("tdb", "store_config"))).thenReturn(store_config_table);

        String table = columnTableResolver.selectTableNameFromCandidates(Collections.singletonList("store_config"), "store_id");

        assertEquals("store_config", table);
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
            ColumnReference from_city_id = foreignKey.from().get(0);
            ColumnReference to_city_id = foreignKey.to().get(0);
            assertEquals("address.city_id", from_city_id.toString());

            assertEquals("city.city_id", to_city_id.toString());
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
            ColumnReference from_city_id = foreignKey.from().get(0);
            ColumnReference to_city_id = foreignKey.to().get(0);
            assertEquals("some_schema.address.city_id", from_city_id.toString());
            assertEquals("some_schema.city.city_id", to_city_id.toString());
        }
    }
}
