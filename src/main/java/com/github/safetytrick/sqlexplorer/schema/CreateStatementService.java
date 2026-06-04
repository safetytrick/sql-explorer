package com.github.safetytrick.sqlexplorer.schema;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.inject.Named;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Service;

/**
 * Loads the {@code show create table} statement for a table on demand and
 * caches it. This is intentionally kept out of {@link SchemaLoadingService}'s
 * up-front load because running {@code show create table} per table is slow
 * (relatively) and the create statement is only needed when the user opens the
 * "Create View" for a single table.
 *
 * <p>The cache is keyed on the {@link TableStructure} (which no longer carries
 * the create statement itself), so a reload that produces a structurally
 * different table naturally misses and reloads, while an unchanged structure
 * keeps its cached statement.
 */
@DependsOnDatabaseInitialization
@Service
public class CreateStatementService {

    private static final Logger log = LoggerFactory.getLogger(CreateStatementService.class);

    private static final Set<String> SYSTEM_SCHEMAS =
        Set.of("performance_schema", "sys", "mysql", "information_schema");

    private final DataSource metadataDataSource;
    private final Map<TableStructure, String> cache = new ConcurrentHashMap<>();

    public CreateStatementService(@Named(MetadataDataSource.NAME) DataSource metadataDataSource) {
        this.metadataDataSource = metadataDataSource;
    }

    public String getCreateStatement(TableStructure table) {
        return cache.computeIfAbsent(table, this::loadCreateStatement);
    }

    private String loadCreateStatement(TableStructure table) {
        if (SYSTEM_SCHEMAS.contains(table.schema())) {
            return "system table";
        }
        log.debug("Reading create statement for {}.{}", table.schema(), table.name());
        try (Connection connection = metadataDataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                 "show create table `" + table.schema() + "`.`" + table.name() + "`")) {
            if (!resultSet.next()) {
                return "";
            }
            return resultSet.getString(2);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
