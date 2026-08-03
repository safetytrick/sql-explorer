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
                    `org` . `id` AS `createdBy` ,
                    `CONCAT` ( `org` . `first_name` ,
                    ? ,
                    `org` . `last_name` ) AS `createdByName` ,
                    `CONCAT` ( `org2` . `first_name` ,
                    ? ,
                    `org2` . `last_name` ) AS `deletedByName` ,
                    `report_config_field` . `deleted_on` AS `deletedOn` ,
                    `report_config_field` . `deleted_by` AS `deletedBy`
                FROM
                    `report_config_field`
                JOIN
                    `report`
                        ON `report_config_field` . `report_id` = `report` . `id`
                JOIN
                    `tdb` . `org` AS ORG
                        ON `report_config_field` . `created_by` = `org` . `id`
                LEFT JOIN
                    `tdb` . `org` AS `org2`
                        ON `report_config_field` . `deleted_by` = `org2` . `id`
                WHERE
                    `report_id` = ?
            """;

        Statement statement = CCJSqlParserUtil.parse(sql);

        Map<String, String> aliases = new ExtendedTableNamesFinder().resolveAliases(statement);

        assertEquals(Map.of("`report_config_field`", "`report_config_field`", "`report`", "`report`", "org", "`tdb`.`org`", "`org2`", "`tdb`.`org`"), aliases);
    }
}
