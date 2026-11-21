package com.github.safetytrick.sqlexplorer.evidence.xstatementanalysis;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.github.safetytrick.sqlexplorer.evidence.source.MeteredSql;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class XStatementAnalysisDao {

    private static final Logger log = LoggerFactory.getLogger(XStatementAnalysisDao.class);
    
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
    private static final String VARIABLES_SQL = """
        SHOW VARIABLES WHERE Variable_name IN (
            'performance_schema_digests_size',
            'performance_schema_max_digest_length',
            'max_digest_length',
            'version')
        """;

    private static final String STATUS_SQL = """
        SHOW GLOBAL STATUS WHERE Variable_name IN (
            'Performance_schema_digest_lost',
            'Uptime')
        """;

    private final Connection connection;

    public XStatementAnalysisDao(Connection connection) {
        this.connection = connection;
    }

    public Map<String, String> getCaptureContext() {
        Map<String, String> context = new LinkedHashMap<>();
        read(VARIABLES_SQL, context);
        read(STATUS_SQL, context);
        return context;
    }

    private void read(String sql, Map<String, String> into) {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                into.put(resultSet.getString(1), resultSet.getString(2));
            }
        } catch (SQLException e) {
            log.warn("could not read capture context: {}", e.getMessage());
        }
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
