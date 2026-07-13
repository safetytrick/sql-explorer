package com.github.safetytrick.sqlexplorer.evidence;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.evidence.source.SqlInput;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CompositeSqlRepositoryTest {

    @Test
    void sourceCombinesAllRepositories() {
        CompositeSqlRepository composite = new CompositeSqlRepository(List.of(
            repository(source("primary", Instant.parse("2026-07-01T00:00:00Z"), 1)),
            repository(source("boot4-smoke", Instant.parse("2026-06-01T00:00:00Z"), 2))));

        SqlSource source = composite.getSource();

        assertThat(source.getSourceRef()).isEqualTo("primary+boot4-smoke");
        assertThat(source.getCreatedOn()).isEqualTo(Instant.parse("2026-07-01T00:00:00Z"));
        assertThat(source.getSql().map(SqlInput::rank)).containsExactly(1, 2);
    }

    @Test
    void emptyCompositeHasAnEmptySource() {
        SqlSource source = new CompositeSqlRepository(List.of()).getSource();

        assertThat(source.getSourceRef()).isEmpty();
        assertThat(source.getCreatedOn()).isNull();
        assertThat(source.getSql()).isEmpty();
    }

    private static SqlRepository repository(SqlSource source) {
        SqlRepository repository = mock(SqlRepository.class);
        when(repository.getSource()).thenReturn(source);
        return repository;
    }

    private static SqlSource source(String sourceRef, Instant createdOn, int rank) {
        return new SqlSource() {
            @Override
            public Stream<SqlInput> getSql() {
                return Stream.of(new SqlInput(rank, null, this));
            }

            @Override
            public Instant getCreatedOn() {
                return createdOn;
            }

            @Override
            public String getSourceRef() {
                return sourceRef;
            }
        };
    }
}
