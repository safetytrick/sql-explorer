package com.github.safetytrick.sqlexplorer.analysis;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import net.sf.jsqlparser.JSQLParserException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TableNameAnalyzerTest {

    SqlAnalysisService sqlAnalysisService;
    private SqlProfile.Builder builder;

    @BeforeEach
    public void setUp() {
        sqlAnalysisService = new SqlAnalysisService(new TableNameAnalyzer());
        builder = SqlProfile.newBuilder();
    }

    @Test
    public void testTableNamesWithAliases() throws JSQLParserException {
        String sql = """
            SELECT `pvd` . `store_id` FROM `customer_visit` `pvd` \
            LEFT JOIN `store` `f` ON `f` . `id` = `pvd` . `store_id` \
            LEFT JOIN `store_config` `fc` ON ( `f` . `id` = `fc` . `id` AND `fc` . `name` = ? ) \
            WHERE `pvd` . `customer_id` = ? \
            AND `pvd` . `checkin_date` >= SUBDATE ( NOW ( ) , `IFNULL` ( `fc` . `value` , ? ) ) \
            AND `f` . `type` IN (?) GROUP BY `pvd` . `store_id` \
            """;

        sqlAnalysisService.analyzeSql(sql, builder);
        List<String> tableNames = builder.getTables().stream().map(TableReference::getNormalizedTableName).collect(Collectors.toList());
        assertEquals(Arrays.asList("customer_visit", "store", "store_config"), tableNames);

    }
}
