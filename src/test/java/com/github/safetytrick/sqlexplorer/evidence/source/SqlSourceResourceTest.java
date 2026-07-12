package com.github.safetytrick.sqlexplorer.evidence.source;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.environment.Environment;
import com.github.safetytrick.sqlexplorer.environment.EnvironmentContext;
import com.github.safetytrick.sqlexplorer.environment.EnvironmentRegistry;
import com.github.safetytrick.sqlexplorer.evidence.EnvironmentLoader;
import com.github.safetytrick.sqlexplorer.evidence.SqlRepositoryManager;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSourceResource.SourceRefRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SqlSourceResourceTest {

    private SqlSourceEntityRepository sqlSourceEntityRepository;
    private SourceRefEntityRepository sourceRefEntityRepository;
    private EnvironmentLoader environmentLoader;
    private EnvironmentRegistry environmentRegistry;
    private EnvironmentContext environmentContext;
    private SqlSourceResource resource;

    @BeforeEach
    void setUp() {
        sqlSourceEntityRepository = mock(SqlSourceEntityRepository.class);
        sourceRefEntityRepository = mock(SourceRefEntityRepository.class);
        environmentLoader = mock(EnvironmentLoader.class);
        environmentRegistry = mock(EnvironmentRegistry.class);
        environmentContext = mock(EnvironmentContext.class);
        resource = new SqlSourceResource(mock(SqlRepositoryManager.class), environmentLoader,
            environmentContext, environmentRegistry,
            sqlSourceEntityRepository, sourceRefEntityRepository, mock(SqlSourceEntityService.class));

        // persisted uploads: "mine" attributed to prod, "theirs" to staging, "legacy" unattributed
        when(sqlSourceEntityRepository.findDistinctBy()).thenReturn(List.of(
            new SourceRefRequest("mine"), new SourceRefRequest("theirs"), new SourceRefRequest("legacy")));
        when(sourceRefEntityRepository.findAll()).thenReturn(List.of(
            sourceRef("mine", "prod"), sourceRef("theirs", "staging")));
        for (String ref : List.of("mine", "theirs", "legacy")) {
            when(sqlSourceEntityRepository.findLatestBySourceRef(ref)).thenReturn(Optional.of(entity(ref)));
        }
    }

    @Test
    void loadWithoutBodyLoadsAttributedAndUnattributedRefs() {
        when(environmentRegistry.get("prod")).thenReturn(new Environment("prod", List.of()));

        resource.loadEnvironment("prod", null);

        assertThat(loadedSourceRefs("prod")).containsExactly("mine", "legacy");
    }

    @Test
    void explicitlyRequestedRefsAreHonoredRegardlessOfAttribution() {
        when(environmentRegistry.get("prod")).thenReturn(new Environment("prod", List.of()));

        resource.loadEnvironment("prod", List.of(new SourceRefRequest("theirs")));

        assertThat(loadedSourceRefs("prod")).containsExactly("theirs");
    }

    @Test
    void reloadSourcesScopesToTheRequestEnvironment() {
        when(environmentContext.current()).thenReturn(new Environment("staging", List.of()));

        resource.reloadSources(null);

        assertThat(loadedSourceRefs("staging")).containsExactly("theirs", "legacy");
    }

    @Test
    void loadUnknownEnvironmentFails() {
        assertThatThrownBy(() -> resource.loadEnvironment("nope", null))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listingIsScopedToTheRequestEnvironment() {
        when(environmentContext.current()).thenReturn(new Environment("prod", List.of()));

        List<String> listed = resource.listPersistentImportedSqlSources()
            .map(SqlSourceEntity::getSourceRef).toList();

        assertThat(listed).containsExactly("legacy", "mine");
    }

    private List<String> loadedSourceRefs(String environmentName) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Stream<SqlSource>> captor = ArgumentCaptor.forClass(Stream.class);
        verify(environmentLoader).load(eq(environmentName), captor.capture());
        return captor.getValue().map(SqlSource::getSourceRef).toList();
    }

    private static SourceRefEntity sourceRef(String name, String environmentName) {
        SourceRefEntity ref = new SourceRefEntity();
        ref.setName(name);
        ref.setEnvironmentName(environmentName);
        return ref;
    }

    private static SqlSourceEntity entity(String sourceRef) {
        SqlSourceEntity entity = new SqlSourceEntity();
        entity.setSourceRef(sourceRef);
        return entity;
    }
}
