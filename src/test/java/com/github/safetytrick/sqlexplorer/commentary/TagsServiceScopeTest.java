package com.github.safetytrick.sqlexplorer.commentary;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    void readingByTargetIsScoped() {
        service.findAllByTarget(TagTarget.ofSchema("mysql"));

        verify(tagRepository).findAllByTypeAndSchemaNameAndEnvironmentName("schema", "mysql", ENV);
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
