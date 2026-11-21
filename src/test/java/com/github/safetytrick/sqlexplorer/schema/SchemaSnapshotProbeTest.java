package com.github.safetytrick.sqlexplorer.schema;

import java.nio.file.Path;

import com.github.safetytrick.sqlexplorer.analysis.IgnoredSchemas;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "GRANTS_PROBE_URL", matches = ".+")
class SchemaSnapshotProbeTest {

    @TempDir
    Path directory;

    @Test
    void aRealSchemaSurvivesItsSnapshot() {
        DriverManagerDataSource reader = new DriverManagerDataSource(System.getenv("GRANTS_PROBE_URL"),
            "p_select_showview", "pw");
        SchemaRepositoryFactory factory = new SchemaRepositoryFactory(
            new TableColumnService(new ColumnEncodingService()), new IndexLoadingService(),
            new ForeignKeyLoadingService(), new CreateStatementService(new IgnoredSchemas()),
            new ViewDefinitionService(), new SchemaFingerprintService(), new SchemaSnapshotStore(directory));
        SchemaSnapshot.Key key = new SchemaSnapshot.Key("probe", "fixture");

        SchemaRepositoryFactory.SchemaLoad read = factory.loadCurrent(reader, key, "fixture");
        SchemaRepositoryFactory.SchemaLoad snapshotted = factory.loadCurrent(reader, key, "fixture");

        assertThat(read.fromSnapshot()).isFalse();
        assertThat(snapshotted.fromSnapshot()).isTrue();
        assertThat(snapshotted.schema().getTables()).hasSameSizeAs(read.schema().getTables())
            .containsExactlyInAnyOrderElementsOf(read.schema().getTables());
        TableStructure customer = snapshotted.schema().getTable(
            com.github.safetytrick.sqlexplorer.avro.TableReference.newTableReference("app", "customer"));
        assertThat(snapshotted.schema().getCreateStatement(customer)).containsIgnoringCase("create table");
    }
}
