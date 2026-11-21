package com.github.safetytrick.sqlexplorer.analysis;

import java.util.List;
import java.util.stream.Collectors;

import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import net.sf.jsqlparser.JSQLParserException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
            SELECT `pvd` . `branch_id` FROM `member_session` `pvd` \
            LEFT JOIN `branch` `f` ON `f` . `id` = `pvd` . `branch_id` \
            LEFT JOIN `branch_config` `fc` ON ( `f` . `id` = `fc` . `id` AND `fc` . `name` = ? ) \
            WHERE `pvd` . `member_id` = ? \
            AND `pvd` . `checkin_date` >= SUBDATE ( NOW ( ) , `IFNULL` ( `fc` . `value` , ? ) ) \
            AND `f` . `type` IN (?) GROUP BY `pvd` . `branch_id` \
            """;

        sqlAnalysisService.analyzeSql(sql, builder);
        List<String> tableNames = builder.getTables().stream().map(TableReference::getNormalizedTableName).collect(Collectors.toList());
        assertThat(tableNames).containsExactlyInAnyOrder("member_session", "branch", "branch_config");

    }
}
