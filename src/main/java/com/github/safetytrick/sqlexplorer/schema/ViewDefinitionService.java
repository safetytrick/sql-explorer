package com.github.safetytrick.sqlexplorer.schema;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.github.safetytrick.sqlexplorer.analysis.NameNormalizer;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Resolves the base tables a view selects from. Definitions are bulk-loaded
 * from {@code information_schema.VIEWS} at schema-load time and parsed with
 * jsqlparser; a view whose definition can't be read (privileges) or parsed
 * yields an empty list rather than failing the schema load.
 */
@Service
public class ViewDefinitionService {

    private static final Logger log = LoggerFactory.getLogger(ViewDefinitionService.class);

    private static final String VIEW_DEFINITIONS =
        "SELECT TABLE_SCHEMA, TABLE_NAME, VIEW_DEFINITION FROM information_schema.VIEWS";

    /** A view's definition text and the base tables parsed out of it. */
    public record ViewDefinition(String schema, String name, String definition,
                                 List<TableReference> underlyingTables) { }

    /**
     * Definition + underlying tables for every view on the server, keyed by
     * {@link #key(String, String) normalized "schema.table"}.
     */
    public Map<String, ViewDefinition> loadViewDefinitions(DataSource dataSource) {
        Map<String, ViewDefinition> byView = new HashMap<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(VIEW_DEFINITIONS);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                String schema = resultSet.getString(1);
                String name = resultSet.getString(2);
                String definition = resultSet.getString(3);
                byView.put(key(schema, name),
                    new ViewDefinition(schema, name, definition, parseUnderlyingTables(definition, schema)));
            }
        } catch (SQLException e) {
            // schema load should survive a server without information_schema.VIEWS access
            log.warn("Failed to load view definitions: {}", e.getMessage());
        }
        return byView;
    }

    public static String key(String schema, String name) {
        return normalize(schema) + "." + normalize(name);
    }

    private static String normalize(String name) {
        return name == null ? null : NameNormalizer.normalize(name);
    }

    /**
     * Parse a view definition (its SELECT text) into the distinct base tables
     * it references, in first-reference order. Table names without an explicit
     * schema default to {@code defaultSchema}. Unreadable or unparseable
     * definitions yield an empty list.
     */
    public static List<TableReference> parseUnderlyingTables(String definition, String defaultSchema) {
        if (definition == null || definition.isBlank()) {
            return List.of();
        }
        try {
            Statement statement = CCJSqlParserUtil.parse(definition);
            return underlyingTables(statement, defaultSchema);
        } catch (Exception e) {
            log.debug("Unparseable view definition (schema {}): {}", defaultSchema, e.getMessage());
            return List.of();
        }
    }

    /** The distinct base tables referenced by an already-parsed statement (e.g. a view's SELECT). */
    public static List<TableReference> underlyingTables(Statement statement, String defaultSchema) {
        Set<String> names = new TablesNamesFinder<Void>().getTables(statement);

        Map<String, TableReference> distinct = new LinkedHashMap<>();
        for (String fullyQualified : names) {
            TableReference reference = toReference(fullyQualified, defaultSchema);
            distinct.putIfAbsent(reference.getNormalizedSchemaName() + "." + reference.getNormalizedTableName(),
                reference);
        }
        return new ArrayList<>(distinct.values());
    }

    private static TableReference toReference(String fullyQualified, String defaultSchema) {
        String[] parts = fullyQualified.split("\\.");
        String table = normalize(parts[parts.length - 1]);
        String schema = parts.length > 1
            ? normalize(parts[parts.length - 2])
            : normalize(defaultSchema);
        return TableReference.newTableReference(schema, table);
    }
}
