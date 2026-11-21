package com.github.safetytrick.sqlexplorer.commentary;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TagTargetLookupTest {

    private static final String ENV = "regression";

    private TagRepository tagRepository;
    private TagTargetLookup lookup;

    @BeforeEach
    void setUp() {
        tagRepository = mock(TagRepository.class);
        CommentaryEnvironment environment = mock(CommentaryEnvironment.class);
        when(environment.name()).thenReturn(ENV);
        when(tagRepository.findAllByTypeAndSchemaNameInAndEnvironmentName(anyString(), anyList(), anyString()))
            .thenReturn(List.of());
        when(tagRepository.findAllByTypeAndQueryDigestInAndEnvironmentName(anyString(), anyList(), anyString()))
            .thenReturn(List.of());
        lookup = new TagTargetLookup(tagRepository, environment);
    }

    private static Tag tag(String name, TagTarget target) {
        Tag tag = new Tag();
        tag.setName(name);
        tag.target(target);
        return tag;
    }

    @Test
    void answersOnePositionPerTargetInTheOrderAsked() {
        TagTarget orders = TagTarget.ofTable("app_main", "orders");
        TagTarget events = TagTarget.ofTable("app_main", "events");
        TagTarget audit = TagTarget.ofTable("app_main", "audit");
        when(tagRepository.findAllByTypeAndSchemaNameInAndEnvironmentName(eq("table"), anyList(), eq(ENV)))
            .thenReturn(List.of(tag("c", audit), tag("a", orders), tag("b", events)));

        List<List<Tag>> answer = lookup.tagsFor(List.of(orders, events, audit));

        assertThat(answer).hasSize(3);
        assertThat(answer.get(0)).extracting(Tag::getName).containsExactly("a");
        assertThat(answer.get(1)).extracting(Tag::getName).containsExactly("b");
        assertThat(answer.get(2)).extracting(Tag::getName).containsExactly("c");
    }

    @Test
    void anotherTablesTagsInTheSameSchemaAreNotAttributedHere() {
        TagTarget orders = TagTarget.ofTable("app_main", "orders");
        when(tagRepository.findAllByTypeAndSchemaNameInAndEnvironmentName(eq("table"), anyList(), eq(ENV)))
            .thenReturn(List.of(tag("elsewhere", TagTarget.ofTable("app_main", "something_else"))));

        assertThat(lookup.tagsFor(List.of(orders)).get(0)).isEmpty();
    }

    @Test
    void anUntaggedTargetIsAnEmptyListNotAGap() {
        List<List<Tag>> answer = lookup.tagsFor(
            List.of(TagTarget.ofSchema("app_main"), TagTarget.ofSchema("app_other")));

        assertThat(answer).hasSize(2);
        assertThat(answer).allSatisfy(tags -> assertThat(tags).isEmpty());
    }

    @Test
    void aDuplicateTargetGetsTheSameAnswerAtBothPositions() {
        TagTarget appMain = TagTarget.ofSchema("app_main");
        when(tagRepository.findAllByTypeAndSchemaNameInAndEnvironmentName(eq("schema"), anyList(), eq(ENV)))
            .thenReturn(List.of(tag("system", appMain)));

        List<List<Tag>> answer = lookup.tagsFor(List.of(appMain, TagTarget.ofSchema("app_other"), appMain));

        assertThat(answer.get(0)).extracting(Tag::getName).containsExactly("system");
        assertThat(answer.get(1)).isEmpty();
        assertThat(answer.get(2)).extracting(Tag::getName).containsExactly("system");
    }

    @Test
    void kindsAreMixedFreelyInOneRequest() {
        TagTarget schema = TagTarget.ofSchema("app_main");
        TagTarget table = TagTarget.ofTable("app_main", "orders");
        TagTarget query = TagTarget.ofQuery("v3:abc");
        when(tagRepository.findAllByTypeAndSchemaNameInAndEnvironmentName(eq("schema"), anyList(), eq(ENV)))
            .thenReturn(List.of(tag("system", schema)));
        when(tagRepository.findAllByTypeAndSchemaNameInAndEnvironmentName(eq("table"), anyList(), eq(ENV)))
            .thenReturn(List.of(tag("clickhouse", table)));
        when(tagRepository.findAllByTypeAndQueryDigestInAndEnvironmentName(eq("query"), anyList(), eq(ENV)))
            .thenReturn(List.of(tag("slow", query)));

        List<List<Tag>> answer = lookup.tagsFor(List.of(query, schema, table));

        assertThat(answer.get(0)).extracting(Tag::getName).containsExactly("slow");
        assertThat(answer.get(1)).extracting(Tag::getName).containsExactly("system");
        assertThat(answer.get(2)).extracting(Tag::getName).containsExactly("clickhouse");
    }

    @Test
    void aTableNameContainingASeparatorCanBeAskedAboutAndAnswered() {
        TagTarget odd = TagTarget.ofTable("probe", "a/b");
        when(tagRepository.findAllByTypeAndSchemaNameInAndEnvironmentName(eq("table"), anyList(), eq(ENV)))
            .thenReturn(List.of(tag("clickhouse", odd)));

        assertThat(lookup.tagsFor(List.of(odd)).get(0)).extracting(Tag::getName)
            .containsExactly("clickhouse");
        assertThat(odd.path()).as("and it has no path form at all").isNull();
    }

    @Test
    void anEmptyRequestIsAnEmptyAnswer() {
        assertThat(lookup.tagsFor(List.of())).isEmpty();
        assertThat(lookup.tagsFor(null)).isEmpty();
    }
}
