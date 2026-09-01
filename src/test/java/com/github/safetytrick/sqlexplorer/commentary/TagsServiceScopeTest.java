package com.github.safetytrick.sqlexplorer.commentary;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every read of a tag is scoped to one environment.
 *
 * <p>THE FAILURE MODE IS SILENCE. A query that forgets the scope does not throw
 * — it returns another environment's tags, which look exactly like this one's.
 * `regression` and `local` share `information_schema`, `mysql`,
 * `performance_schema` and `sys`, so the wrong rows are available to be
 * returned on the very schemas the unqueried page tells you to tag (sqlx-rad).
 *
 * <p>These pin the scope at {@link TagsService}, which is the whole read
 * surface outside this package: profile building, query search and the graph
 * all come through it and none of them mention environments.
 */
class TagsServiceScopeTest {

    private static final String ENV = "regression";

    private TagRepository tagRepository;
    private TagsService service;

    @BeforeEach
    void setUp() {
        tagRepository = mock(TagRepository.class);
        CommentaryEnvironment environment = mock(CommentaryEnvironment.class);
        when(environment.name()).thenReturn(ENV);
        when(tagRepository.findAllByTypeAndEnvironmentName(anyString(), anyString())).thenReturn(List.of());
        when(tagRepository.findAllByTypeAndSchemaNameAndEnvironmentName(anyString(), anyString(), anyString()))
            .thenReturn(List.of());
        service = new TagsService(tagRepository, environment);
    }

    @Test
    void readingByPathIsScoped() {
        service.findAllByPath("/schema/mysql");

        verify(tagRepository).findAllByPathAndEnvironmentName("/schema/mysql", ENV);
    }

    @Test
    void readingByTypeIsScoped() {
        service.findAllByType("schema");

        verify(tagRepository).findAllByTypeAndEnvironmentName("schema", ENV);
    }

    @Test
    void readingByNameAndTypeIsScoped() {
        service.findAllByNameAndType("system", "schema");

        verify(tagRepository).findAllByNameAndTypeAndEnvironmentName("system", "schema", ENV);
    }

    /**
     * The bulk loaders are the ones a list page uses for hundreds of rows at
     * once, so an unscoped one would show another environment's tags across a
     * whole table rather than on a single row.
     */
    @Test
    void theBulkLoadersAreScoped() {
        service.getBulkTagLoaderByType("table");
        verify(tagRepository).findAllByTypeAndEnvironmentName("table", ENV);

        // Was a path-prefix LIKE; now a predicate on the column that holds the
        // schema (sqlx-11v).
        service.getTableTagLoaderForSchema("app_main");
        verify(tagRepository).findAllByTypeAndSchemaNameAndEnvironmentName("table", "app_main", ENV);
    }
}
