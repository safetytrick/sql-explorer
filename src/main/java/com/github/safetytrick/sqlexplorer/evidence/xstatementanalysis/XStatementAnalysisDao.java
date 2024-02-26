package com.github.safetytrick.sqlexplorer.evidence.xstatementanalysis;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import com.github.safetytrick.sqlexplorer.evidence.source.MeteredSql;

class XStatementAnalysisDao {
    
    private static final String saQueryText =
        """
        SELECT
            query,
            db,
            full_scan,
            exec_count,
            err_count,
            warn_count,
            total_latency,
            max_latency,
            avg_latency,
            lock_latency,
            rows_sent,
            rows_sent_avg,
            rows_examined,
            rows_examined_avg,
            rows_affected,
            rows_affected_avg,
            tmp_tables,
            tmp_disk_tables,
            rows_sorted,
            sort_merge_passes,
            digest,
            first_seen,
            last_seen
        FROM sys.x$statement_analysis
        """;
    private final Connection connection;

    public XStatementAnalysisDao(Connection connection) {
        this.connection = connection;
    }

    public List<MeteredSql> getXStatementAnalysis() throws SQLException {
        try (PreparedStatement saQuery = connection.prepareStatement(saQueryText);
             ResultSet resultSet = saQuery.executeQuery()) {

            List<MeteredSql> result = new ArrayList<>();
            while (resultSet.next()) {
                var meteredSql = new MeteredSql(
                    resultSet.getString("query"),
                    resultSet.getString("db"),
                    resultSet.getString("full_scan"),
                    resultSet.getLong("exec_count"),
                    resultSet.getLong("err_count"),
                    resultSet.getLong("warn_count"),
                    resultSet.getBigDecimal("total_latency"),
                    resultSet.getBigDecimal("max_latency"),
                    resultSet.getBigDecimal("avg_latency"),
                    resultSet.getBigDecimal("lock_latency"),
                    resultSet.getLong("rows_sent"),
                    resultSet.getLong("rows_sent_avg"),
                    resultSet.getLong("rows_affected"),
                    resultSet.getLong("rows_affected_avg"),
                    resultSet.getLong("rows_examined"),
                    resultSet.getLong("rows_examined_avg"),
                    resultSet.getLong("tmp_tables"),
                    resultSet.getLong("tmp_disk_tables"),
                    resultSet.getLong("rows_sorted"),
                    resultSet.getLong("sort_merge_passes"),
                    resultSet.getString("digest"),
                    resultSet.getTimestamp("first_seen").toLocalDateTime(),
                    resultSet.getTimestamp("last_seen").toLocalDateTime()
                );
                result.add(meteredSql);
            }

            return result;
        }
    }
}
