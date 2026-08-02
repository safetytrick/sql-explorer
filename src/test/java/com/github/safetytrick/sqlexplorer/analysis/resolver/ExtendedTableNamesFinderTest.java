package com.github.safetytrick.sqlexplorer.analysis.resolver;

import java.util.Map;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExtendedTableNamesFinderTest {
    @Test
    void find_fqn_for_cross_schema_query() throws JSQLParserException {
        String sql = """
                SELECT
                    `report_config_field` . `id` ,
                    `report_id` AS `reportId` ,
                    `report_config_field_type` AS `configFieldType` ,
                    VALUE ,
                    `report_config_field` . `created_on` AS `createdOn` ,
                    `tenant` . `id` AS `createdBy` ,
                    `CONCAT` ( `tenant` . `first_name` ,
                    ? ,
                    `tenant` . `last_name` ) AS `createdByName` ,
                    `CONCAT` ( `tenant2` . `first_name` ,
                    ? ,
                    `tenant2` . `last_name` ) AS `deletedByName` ,
                    `report_config_field` . `deleted_on` AS `deletedOn` ,
                    `report_config_field` . `deleted_by` AS `deletedBy`
                FROM
                    `report_config_field`
                JOIN
                    `report`
                        ON `report_config_field` . `report_id` = `report` . `id`
                JOIN
                    `tdb` . `tenant` AS TENANT
                        ON `report_config_field` . `created_by` = `tenant` . `id`
                LEFT JOIN
                    `tdb` . `tenant` AS `tenant2`
                        ON `report_config_field` . `deleted_by` = `tenant2` . `id`
                WHERE
                    `report_id` = ?
            """;

        Statement statement = CCJSqlParserUtil.parse(sql);

        Map<String, String> aliases = new ExtendedTableNamesFinder().resolveAliases(statement);

        assertEquals(Map.of("`report_config_field`", "`report_config_field`", "`report`", "`report`", "tenant", "`tdb`.`tenant`", "`tenant2`", "`tdb`.`tenant`"), aliases);
    }
}
