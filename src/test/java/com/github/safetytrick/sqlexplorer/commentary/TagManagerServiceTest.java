package com.github.safetytrick.sqlexplorer.commentary;

import java.time.Instant;
import java.util.List;

import com.github.safetytrick.sqlexplorer.commentary.TagManagerService.TagSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TagManagerServiceTest {

    private static final Instant RECENT = Instant.parse("2026-07-10T00:00:00Z");
    private static final Instant OLD = Instant.parse("2026-06-01T00:00:00Z");

    private TagRepository tagRepository;
    private TagDefinitionRepository tagDefinitionRepository;
    private TagManagerService service;

    @BeforeEach
    void setUp() {
        tagRepository = mock(TagRepository.class);
        tagDefinitionRepository = mock(TagDefinitionRepository.class);
        service = new TagManagerService(tagRepository, tagDefinitionRepository);
    }

    @Test
    void mergesDefinitionsWithUsageNewestFirst() {
        when(tagDefinitionRepository.findAll()).thenReturn(List.of(
            definition("audit", "#4a9eff"),
            definition("unused", "#f5a623")));
        when(tagRepository.aggregateUsage()).thenReturn(List.of(
            usage("audit", "query", 4, OLD),
            usage("audit", "table", 2, RECENT),
            // legacy tag: assignments exist but no definition row
            usage("legacy", "query", 1, RECENT)));

        List<TagSummary> summaries = service.getSummaries();

        assertThat(summaries).extracting(TagSummary::name, TagSummary::color,
                TagSummary::queries, TagSummary::tables, TagSummary::lastUsed)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("audit", "#4a9eff", 4L, 2L, RECENT),
                org.assertj.core.groups.Tuple.tuple("legacy", null, 1L, 0L, RECENT),
                org.assertj.core.groups.Tuple.tuple("unused", "#f5a623", 0L, 0L, null));
    }

    @Test
    void deleteRemovesDefinitionAndAssignments() {
        service.delete("audit");

        verify(tagDefinitionRepository).deleteById("audit");
        verify(tagRepository).deleteAllByName("audit");
    }

    private static TagDefinition definition(String name, String color) {
        TagDefinition definition = new TagDefinition();
        definition.setName(name);
        definition.setColor(color);
        return definition;
    }

    private static TagRepository.TagUsage usage(String name, String type, long uses, Instant lastUsed) {
        return new TagRepository.TagUsage() {
            @Override public String getName() { return name; }
            @Override public String getType() { return type; }
            @Override public long getUses() { return uses; }
            @Override public Instant getLastUsed() { return lastUsed; }
        };
    }
}
