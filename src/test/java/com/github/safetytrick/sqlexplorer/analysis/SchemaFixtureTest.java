package com.github.safetytrick.sqlexplorer.analysis;

import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.schema.TableStructure;
import extensions.com.github.safetytrick.sqlexplorer.avro.ColumnReference.ColumnReferenceExtension;
import net.sf.jsqlparser.JSQLParserException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The parser test framework end to end: define a schema as SQL, analyze a
 * query against it, assert on the profile.
 */
class SchemaFixtureTest {

    private static final String DDL = """
        create table `foo` (
          `id` bigint(19) NOT NULL AUTO_INCREMENT,
          `bar_id` bigint(19) DEFAULT NULL,
          `label` varchar(64) NOT NULL DEFAULT 'none',
          PRIMARY KEY (`id`),
          KEY `foo__bar_id_idx` (`bar_id`)
        );
        create table `bar` (
          `id` bigint(19) NOT NULL,
          `name` varchar(64) DEFAULT NULL,
          PRIMARY KEY (`id`),
          UNIQUE KEY `bar__name_uq` (`name`)
        );
        """;

    @Test
    void analyzesAJoinAgainstAnInlineSchema() throws JSQLParserException {
        SchemaFixture fixture = SchemaFixture.fromSql(DDL);

        SqlProfile profile = fixture.analyze(
            "select f.label, b.name from foo f join bar b on f.bar_id = b.id where name = ?");

        assertThat(profile.getTables())
            .extracting(TableReference::getNormalizedTableName)
            .containsExactlyInAnyOrder("foo", "bar");
        assertThat(profile.getJoinColumns()).hasSize(1);
        assertThat(profile.getJoinColumns().getFirst().getLeft().toStringNormalized())
            .isEqualTo("tdb.foo.bar_id");
        // the unqualified where-column resolves through the schema: name is only in bar
        assertThat(profile.getCriteriaColumns())
            .anyMatch(column -> column.equalsNormalized("tdb.bar.name"));
        assertThat(profile.getSelectColumns())
            .extracting(ColumnReferenceExtension::toStringNormalized)
            .contains("tdb.foo.label", "tdb.bar.name");
        assertThat(profile.getAnalysisFailed()).isFalse();
    }

    @Test
    void inlineSchemaCarriesTheStructureAttributes() throws JSQLParserException {
        SchemaFixture fixture = SchemaFixture.fromSql(DDL);

        TableStructure foo = fixture.schema().getTable(TableReference.newTableReference("tdb", "foo"));
        assertThat(foo.columns()).extracting(c -> c.columnName()).containsExactly("id", "bar_id", "label");
        assertThat(foo.indices()).hasSize(2);

        TableStructure bar = fixture.schema().getTable(TableReference.newTableReference("tdb", "bar"));
        assertThat(bar.indices())
            .filteredOn(index -> "bar__name_uq".equals(index.name()))
            .singleElement()
            .satisfies(index -> assertThat(index.unique()).isTrue());
    }

    @Test
    void resourceFixturesLoadTheSameWay() throws JSQLParserException {
        SchemaFixture fixture = SchemaFixture.fromResources("branch", "member_session");

        SqlProfile profile = fixture.analyze(
            "select checkin_date, name from member_session ms join branch b on ms.branch_id = b.id");

        assertThat(profile.getCriteriaColumns()).isEmpty();
        assertThat(profile.getSelectColumns())
            .extracting(ColumnReferenceExtension::toStringNormalized)
            .contains("tdb.member_session.checkin_date", "tdb.branch.name");
    }
}
