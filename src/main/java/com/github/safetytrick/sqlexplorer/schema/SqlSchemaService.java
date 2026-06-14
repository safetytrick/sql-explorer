package com.github.safetytrick.sqlexplorer.schema;

import java.util.List;

import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.environment.EnvironmentContext;
import org.springframework.stereotype.Service;

/**
 * The schema of the environment selected for the current request (resolved via
 * {@link EnvironmentContext} from the {@code X-Sql-Explorer-Environment} header,
 * defaulting to the default environment). Used on the REST query path; the
 * import path resolves its schema explicitly per server.
 */
@Service
public class SqlSchemaService implements SqlSchemaRepository {

    private final EnvironmentContext environmentContext;

    public SqlSchemaService(EnvironmentContext environmentContext) {
        this.environmentContext = environmentContext;
    }

    private SqlSchemaRepository activeSchema() {
        return environmentContext.current().schemaRepository();
    }

    @Override
    public TableStructure getTable(TableReference table) {
        return activeSchema().getTable(table);
    }

    @Override
    public List<TableStructure> getTables() {
        return activeSchema().getTables();
    }

    @Override
    public List<TableStructure> getTablesByName(TableReference table) {
        return activeSchema().getTablesByName(table);
    }

    @Override
    public String getCreateStatement(TableStructure table) {
        return activeSchema().getCreateStatement(table);
    }
}
