package com.github.safetytrick.sqlexplorer.schema;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ForeignKeyLoadingService {
    private static final Logger log = LoggerFactory.getLogger(ForeignKeyLoadingService.class);

    ForeignKeysFetcher getForeignKeysFetcher(DataSource dataSource) {
        log.info("Reading Foreign keys from database...");
        long start = System.nanoTime();
        Map<TableReference, List<ForeignKey>> foreignKeyMap;
        try (Connection connection = dataSource.getConnection();
             ResultSet keys = getImportedKeys(connection)) {
            foreignKeyMap = readForeignKeys(keys);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        log.info("Completed read of foreign keys in {}", Duration.ofNanos(System.nanoTime() - start));

        return table -> foreignKeyMap.getOrDefault(table.normalized(), Collections.emptyList());
    }

    Map<TableReference, List<ForeignKey>> readForeignKeys(ResultSet keys) throws SQLException {
        Map<TableReference, List<ForeignKey>> foreignKeyMap = new HashMap<>();
        TableReference fromTable = null;
        String constraint = null;
        ForeignKey.Builder builder = null;
        List<ForeignKey> result = new ArrayList<>();

        while (keys.next()) {
            String fromSchema = keys.getString("FKTABLE_CAT");
            String fromTableName = keys.getString("FKTABLE_NAME");
            String constraintName = keys.getString("FK_NAME");

            boolean newOwner = fromTable == null || !(
                fromTable.getNormalizedSchemaName().equalsIgnoreCase(fromSchema) &&
                fromTable.getNormalizedTableName().equalsIgnoreCase(fromTableName));

            if (builder != null && (newOwner || !constraintName.equals(constraint))) {
                result.add(builder.build());
                builder = null;
            }
            if (newOwner) {
                if (fromTable != null) {
                    foreignKeyMap.put(fromTable, result);
                    result = new ArrayList<>();
                }
                fromTable = TableReference.newTableReference(fromSchema, fromTableName).normalized();
            }
            if (builder == null) {
                builder = new ForeignKey.Builder()
                    .constraintName(constraintName)
                    .rules(keys.getString("UPDATE_RULE_NAME"), keys.getString("DELETE_RULE_NAME"));
            }
            constraint = constraintName;

            TableReference toTable = TableReference.newTableReference(
                keys.getString("PKTABLE_CAT"), keys.getString("PKTABLE_NAME")).normalized();
            builder.addFromAndTo(
                ColumnReference.newColumnReference(fromTable, keys.getString("FKCOLUMN_NAME")),
                ColumnReference.newColumnReference(toTable, keys.getString("PKCOLUMN_NAME")));
        }
        if (builder != null) {
            result.add(builder.build());
        }
        if (fromTable != null) {
            foreignKeyMap.put(fromTable, result);
        }
        return foreignKeyMap;
    }

    private ResultSet getImportedKeys(Connection connection) throws SQLException {
        String sql = """
           SELECT DISTINCT A.REFERENCED_TABLE_SCHEMA AS PKTABLE_CAT,
               NULL                      AS PKTABLE_SCHEM,
               A.REFERENCED_TABLE_NAME   AS PKTABLE_NAME,
               A.REFERENCED_COLUMN_NAME  AS PKCOLUMN_NAME,
               A.TABLE_SCHEMA            AS FKTABLE_CAT,
               NULL                      AS FKTABLE_SCHEM,
               A.TABLE_NAME              AS FKTABLE_NAME,
               A.COLUMN_NAME             AS FKCOLUMN_NAME,
               A.ORDINAL_POSITION        AS KEY_SEQ,
               CASE
                   WHEN R.UPDATE_RULE = 'CASCADE' THEN 0
                   WHEN R.UPDATE_RULE = 'SET NULL' THEN 2
                   WHEN R.UPDATE_RULE = 'SET DEFAULT' THEN 4
                   WHEN R.UPDATE_RULE = 'RESTRICT' THEN 1
                   WHEN R.UPDATE_RULE = 'NO ACTION' THEN 1
                   ELSE 1 END            AS UPDATE_RULE,
               CASE
                   WHEN R.DELETE_RULE = 'CASCADE' THEN 0
                   WHEN R.DELETE_RULE = 'SET NULL' THEN 2
                   WHEN R.DELETE_RULE = 'SET DEFAULT' THEN 4
                   WHEN R.DELETE_RULE = 'RESTRICT' THEN 1
                   WHEN R.DELETE_RULE = 'NO ACTION' THEN 1
                   ELSE 1 END            AS DELETE_RULE,
               A.CONSTRAINT_NAME         AS FK_NAME,
               R.UNIQUE_CONSTRAINT_NAME  AS PK_NAME,
               7                         AS DEFERRABILITY,
               R.UPDATE_RULE             AS UPDATE_RULE_NAME,
               R.DELETE_RULE             AS DELETE_RULE_NAME
           FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE A
                    JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS B USING (CONSTRAINT_SCHEMA, CONSTRAINT_NAME, TABLE_NAME)
                    JOIN INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS R
           ON (R.CONSTRAINT_SCHEMA = B.CONSTRAINT_SCHEMA AND R.CONSTRAINT_NAME = B.CONSTRAINT_NAME)
           ORDER BY FKTABLE_CAT, FKTABLE_NAME, FK_NAME, KEY_SEQ
           """;


        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            statement.closeOnCompletion();
            if (statement.getMaxRows() != 0) {
                statement.setMaxRows(0);
            }

            return statement.executeQuery();
        } catch (SQLException e) {
            // no ResultSet was produced, so closeOnCompletion won't fire; close here.
            statement.close();
            throw e;
        }
    }

    interface ForeignKeysFetcher {
        List<ForeignKey> getForeignKeys(TableReference table);
    }
}
