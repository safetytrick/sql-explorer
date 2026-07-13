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

    /*
     *  PKTABLE_CAT String => primary key table catalog being imported (may be null)
     *  PKTABLE_SCHEM String => primary key table schema being imported (may be null)
     *  PKTABLE_NAME String => primary key table name being imported
     *  PKCOLUMN_NAME String => primary key column name being imported
     *  FKTABLE_CAT String => foreign key table catalog (may be null)
     *  FKTABLE_SCHEM String => foreign key table schema (may be null)
     *  FKTABLE_NAME String => foreign key table name
     *  FKCOLUMN_NAME String => foreign key column name
     *  KEY_SEQ short => sequence number within a foreign key( a value of 1 represents the first column of the foreign key, a value of 2 would represent the second column within the foreign key).
     *  UPDATE_RULE short => What happens to a foreign key when the primary key is updated:
     *      importedNoAction - do not allow update of primary key if it has been imported
     *      importedKeyCascade - change imported key to agree with primary key update
     *      importedKeySetNull - change imported key to NULL if its primary key has been updated
     *      importedKeySetDefault - change imported key to default values if its primary key has been updated
     *      importedKeyRestrict - same as importedKeyNoAction (for ODBC 2.x compatibility)
     *  DELETE_RULE short => What happens to the foreign key when primary is deleted.
     *      importedKeyNoAction - do not allow delete of primary key if it has been imported
     *      importedKeyCascade - delete rows that import a deleted key
     *      importedKeySetNull - change imported key to NULL if its primary key has been deleted
     *      importedKeyRestrict - same as importedKeyNoAction (for ODBC 2.x compatibility)
     *      importedKeySetDefault - change imported key to default if its primary key has been deleted
     *  FK_NAME String => foreign key name (may be null)
     *  PK_NAME String => primary key name (may be null)
     *  DEFERRABILITY short => can the evaluation of foreign key constraints be deferred until commit
     *      importedKeyInitiallyDeferred - see SQL92 for definition
     *      importedKeyInitiallyImmediate - see SQL92 for definition
     *      importedKeyNotDeferrable - see SQL92 for definition
     */
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

    /**
     * Groups the (owning-table, constraint, key-seq)-ordered rows into one
     * {@link ForeignKey} per constraint, keyed by the owning table.
     */
    Map<TableReference, List<ForeignKey>> readForeignKeys(ResultSet keys) throws SQLException {
        Map<TableReference, List<ForeignKey>> foreignKeyMap = new HashMap<>();
        TableReference fromTable = null;
        String constraint = null;
        ForeignKey.Builder builder = null;
        List<ForeignKey> result = new ArrayList<>();

        while (keys.next()) {
            // the jdbc driver sends the schema column over in the catalog field
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
            // close the statement when the returned ResultSet is closed by the caller's
            // try-with-resources, so the statement is not orphaned.
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
