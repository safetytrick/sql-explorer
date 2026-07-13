package com.github.safetytrick.sqlexplorer.analysis;

import java.util.List;

import com.github.safetytrick.sqlexplorer.analysis.resolver.ColumnTableResolver;
import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import net.sf.jsqlparser.JSQLParserException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

public class SelectColumnAnalyzerTest {

    private SqlAnalysisService sqlAnalysisService;
    private SqlProfile.Builder builder;

    @BeforeEach
    public void setUp() {
        ColumnTableResolver columnTableResolver = mock(ColumnTableResolver.class);
        sqlAnalysisService = new SqlAnalysisService(new SelectColumnAnalyzer(columnTableResolver));
        builder = SqlProfile.newBuilder();
    }

    @Test
    public void capturesQualifiedSelectColumns() throws JSQLParserException {
        sqlAnalysisService.analyzeSql(
            "SELECT s.id, s.status, u.email FROM sessions s JOIN users u ON s.user_id = u.id", builder);

        assertFalse(builder.getAnalysisFailed());
        assertThat(names(builder.getSelectColumns()))
            .containsExactly("sessions.id", "sessions.status", "users.email");
    }

    @Test
    public void capturesColumnsInsideFunctionsAndGroupBy() throws JSQLParserException {
        sqlAnalysisService.analyzeSql(
            "SELECT COUNT(s.id), s.status FROM sessions s GROUP BY s.status", builder);

        assertThat(names(builder.getSelectColumns())).containsExactly("sessions.id", "sessions.status");
        assertThat(names(builder.getGroupByColumns())).containsExactly("sessions.status");
    }

    @Test
    public void starProjectionContributesNothing() throws JSQLParserException {
        sqlAnalysisService.analyzeSql("SELECT * FROM sessions WHERE id = 1", builder);

        assertThat(builder.getSelectColumns()).isNullOrEmpty();
    }

    @Test
    public void selectColumnsAreDeduplicated() throws JSQLParserException {
        sqlAnalysisService.analyzeSql(
            "SELECT s.id, COALESCE(s.id, 0) FROM sessions s", builder);

        assertThat(names(builder.getSelectColumns())).containsExactly("sessions.id");
    }

    private static List<String> names(List<ColumnReference> columns) {
        return columns == null ? List.of() : columns.stream()
            .map(column -> column.getTable().getNormalizedTableName() + "." + column.getNormalizedColumn())
            .toList();
    }
}
