package com.github.safetytrick.sqlexplorer.evidence;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.avro.QueryActivityStatus;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CompositeSqlRepositoryCacheTest {

    private static final class CountingRepository implements SqlRepository {
        private final AtomicInteger walks = new AtomicInteger();
        private final List<SqlProfile> profiles;

        CountingRepository(List<SqlProfile> profiles) {
            this.profiles = profiles;
        }

        @Override public SqlProfile getByDigest(String id) {
            return profiles.stream().filter(p -> id.equals(p.getId())).findFirst().orElse(null);
        }
        @Override public Stream<SqlProfile> getTableQueries(TableReference table) { return Stream.of(); }
        @Override public Stream<SqlProfile> getQueriesForColumn(TableReference table, String column) { return Stream.of(); }
        @Override public Stream<TableReference> getAllTableReferences() { return Stream.of(); }
        @Override public Stream<SqlProfile> getAllAnalyzedSql() {
            walks.incrementAndGet();
            return profiles.stream();
        }
        @Override public SqlSource getSource() { return null; }
        @Override public SqlImportStats getImportStats() { return SqlImportStats.builder().build(); }
    }

    @Test
    void mergesTheCorpusOnceHoweverManyCallersAskForIt() {
        CountingRepository source = new CountingRepository(List.of(profile("a"), profile("b")));
        CompositeSqlRepository composite = new CompositeSqlRepository(List.of(source));

        for (int i = 0; i < 20; i++) {
            composite.getAllAnalyzedSql().toList();
        }

        assertThat(source.walks).hasValue(1);
    }

    @Test
    void handsEachCallerAUsableStream() {
        CountingRepository source = new CountingRepository(List.of(profile("a"), profile("b")));
        CompositeSqlRepository composite = new CompositeSqlRepository(List.of(source));

        List<SqlProfile> first = composite.getAllAnalyzedSql().toList();
        List<SqlProfile> second = composite.getAllAnalyzedSql().toList();

        assertThat(first).hasSize(2);
        assertThat(second).hasSize(2);
    }

    @Test
    void aNewInstanceStartsCold() {
        CountingRepository source = new CountingRepository(List.of(profile("a")));

        new CompositeSqlRepository(List.of(source)).getAllAnalyzedSql().toList();
        new CompositeSqlRepository(List.of(source)).getAllAnalyzedSql().toList();

        assertThat(source.walks).hasValue(2);
    }

    private static SqlProfile profile(String id) {
        return SqlProfile.newBuilder()
            .setId(id)
            .setDigest(id)
            .setNormalizedDigest(id + "-norm")
            .setQuery("select " + id)
            .setType("SELECT")
            .setSchemaName("app")
            .setQueryActivityStatus(QueryActivityStatus.ACTIVE)
            .setRuntimeMetrics(null)
            .build();
    }
}
