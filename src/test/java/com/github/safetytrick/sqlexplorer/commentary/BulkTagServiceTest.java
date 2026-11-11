package com.github.safetytrick.sqlexplorer.commentary;

import java.util.List;
import java.util.Map;

import com.github.safetytrick.sqlexplorer.auth.User;
import com.github.safetytrick.sqlexplorer.auth.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bulk tagging writes across a whole result set, so the two ways it can go
 * wrong both matter: doing less than it claimed, and doing more than it was
 * asked (sqlx-agl).
 */
class BulkTagServiceTest {

    /** The environment every write in these tests belongs to. */
    private static final String ENV = "regression";

    private TagRepository tagRepository;
    private TagTargetLookup targetLookup;
    private BulkTagService service;

    @BeforeEach
    void setUp() {
        tagRepository = mock(TagRepository.class);
        UserService userService = mock(UserService.class);
        when(userService.currentUser()).thenReturn(mock(User.class));
        when(tagRepository.save(any(Tag.class))).thenAnswer(call -> call.getArgument(0));
        CommentaryEnvironment environment = mock(CommentaryEnvironment.class);
        when(environment.name()).thenReturn(ENV);
        targetLookup = mock(TagTargetLookup.class);
        // Nothing already tagged unless a test says so; answered positionally.
        when(targetLookup.tagsFor(anyList())).thenAnswer(call ->
            ((List<?>) call.getArgument(0)).stream().map(target -> List.<Tag>of()).toList());
        service = new BulkTagService(tagRepository, userService, environment, targetLookup);
    }

    private static Tag tag(String name, TagTarget target) {
        Tag tag = new Tag();
        tag.setId(name.hashCode());
        tag.setName(name);
        tag.target(target);
        return tag;
    }

    /** What the lookup answers for a batch, position by position. */
    @SafeVarargs
    private void lookupAnswers(List<Tag>... answers) {
        when(targetLookup.tagsFor(anyList())).thenReturn(List.of(answers));
    }

    private static final TagTarget MYSQL = TagTarget.ofSchema("mysql");
    private static final TagTarget SYS = TagTarget.ofSchema("sys");

    private List<Tag> saved() {
        ArgumentCaptor<Tag> captor = ArgumentCaptor.forClass(Tag.class);
        verify(tagRepository, atLeast(0)).save(captor.capture());
        return captor.getAllValues();
    }

    // ── attach ──

    @Test
    void attachesTheTagToEveryPath() {
        BulkTagService.BulkResult result = service.attach("system", List.of(MYSQL, SYS), null);

        assertThat(result.changed()).isEqualTo(2);
        assertThat(result.unchanged()).isZero();
        // Asserted on the TYPED target, not on getPath(): the path is a stored
        // generated column now, so it exists only once a row has been written
        // and is null on an entity a mocked repository never persisted
        // (sqlx-11v).
        assertThat(saved()).extracting(Tag::getType, Tag::getSchemaName, Tag::getTableName)
            .containsExactly(tuple("schema", "mysql", ""), tuple("schema", "sys", ""));
    }

    /**
     * Re-running over an overlapping set must not compound. Attaching is keyed
     * on (name, path), so a path already carrying the tag is left alone — and
     * "changed 0" is reported rather than treated as a failure.
     */
    @Test
    void aPathThatAlreadyCarriesTheTagIsLeftAlone() {
        lookupAnswers(List.of(tag("system", MYSQL)), List.of());

        BulkTagService.BulkResult result = service.attach("system", List.of(MYSQL, SYS), null);

        assertThat(result.changed()).isEqualTo(1);
        assertThat(result.unchanged()).isEqualTo(1);
        assertThat(saved()).extracting(Tag::getSchemaName).containsExactly("sys");
    }

    /** A different tag on the same path is not the same association. */
    @Test
    void anotherTagOnThePathDoesNotCountAsAlreadyTagged() {
        lookupAnswers(List.of(tag("audit", MYSQL)));

        assertThat(service.attach("system", List.of(MYSQL), null).changed()).isEqualTo(1);
    }

    /** Each target carries its own kind, and kinds mix freely in one batch. */
    @Test
    void theTypeComesFromEachTarget() {
        service.attach("system", List.of(MYSQL,
            TagTarget.ofTable("mysql", "user"), TagTarget.ofQuery("abc123")), null);

        assertThat(saved()).extracting(Tag::getType).containsExactly("schema", "table", "query");
    }

    /**
     * THE REASON TARGETS REPLACED PATHS HERE. A table whose name contains '/'
     * has no path form, so one such row on a page would have failed the whole
     * batch — every row the user selected staying untagged because of one odd
     * name among hundreds (sqlx-3ai).
     */
    @Test
    void aTableNameContainingASeparatorDoesNotFailTheBatch() {
        BulkTagService.BulkResult result = service.attach("system",
            List.of(TagTarget.ofTable("probe", "a/b"), MYSQL), null);

        assertThat(result.changed()).isEqualTo(2);
        assertThat(saved()).extracting(Tag::getTableName).containsExactly("a/b", "");
    }

    /**
     * The counts have to mean objects, not list entries — a caller can send the
     * same path twice, and reporting "tagged 3" for two schemas would be wrong.
     */
    @Test
    void repeatedPathsAreCountedOnce() {
        BulkTagService.BulkResult result = service.attach("system", List.of(MYSQL, SYS, MYSQL), null);

        assertThat(result.requested()).isEqualTo(2);
        assertThat(result.changed()).isEqualTo(2);
    }

    // ── detach ──

    /**
     * The most destructive operation here, and the one whose scope must be
     * exact. `deleteAllByName` would strip the tag from the entire corpus; only
     * the paths the user was shown may be touched.
     */
    @Test
    void detachIsScopedToTheGivenPathsAndNotTheWholeTag() {
        Tag attached = tag("system", MYSQL);
        lookupAnswers(List.of(attached), List.of());

        BulkTagService.BulkResult result = service.detach("system", List.of(MYSQL, SYS));

        // By the ids the targets resolved to — so nothing carrying the same name
        // outside this set can be touched.
        verify(tagRepository).deleteAllByIdInAndEnvironmentName(List.of(attached.getId()), ENV);
        verify(tagRepository, never()).deleteAllByNameAndEnvironmentName(any(), any());
        assertThat(result.changed()).isEqualTo(1);
        assertThat(result.unchanged()).isEqualTo(1);
    }

    /** Detaching a tag nothing in the set carries is a no-op, not an error. */
    @Test
    void detachingWhatIsNotThereChangesNothing() {
        BulkTagService.BulkResult result = service.detach("system", List.of(MYSQL));

        assertThat(result.changed()).isZero();
        assertThat(result.unchanged()).isEqualTo(1);
    }

    // ── refusals ──

    /**
     * Rejected, never truncated. Silently stopping at the limit would leave the
     * caller believing the whole set was written, which is the worst available
     * outcome — so nothing is written and the message names the limit.
     */
    @Test
    void aSetOverTheCapIsRefusedWithTheLimitNamed() {
        List<TagTarget> tooMany = java.util.stream.IntStream.rangeClosed(0, BulkTagService.MAX_PATHS)
            .mapToObj(i -> TagTarget.ofSchema("s" + i)).toList();

        assertThatThrownBy(() -> service.attach("system", tooMany, null))
            .isInstanceOf(BulkTagRequestException.class)
            .hasMessageContaining(String.valueOf(BulkTagService.MAX_PATHS))
            .hasMessageContaining("Nothing was changed");

        verify(tagRepository, never()).save(any(Tag.class));
    }

    @Test
    void aMalformedTagNameIsRefusedBeforeAnythingIsWritten() {
        assertThatThrownBy(() -> service.attach("not a tag", List.of(MYSQL), null))
            .isInstanceOf(BulkTagRequestException.class);
        verify(tagRepository, never()).save(any(Tag.class));
    }

    @Test
    void anEmptySetIsRefused() {
        assertThatThrownBy(() -> service.attach("system", List.of(), null))
            .isInstanceOf(BulkTagRequestException.class);
        assertThatThrownBy(() -> service.detach("system", null))
            .isInstanceOf(BulkTagRequestException.class);
    }

    /** A target that addresses nothing cannot be tagged blind. */
    @Test
    void aTargetThatAddressesNothingIsRefused() {
        assertThatThrownBy(() ->
            service.attach("system", List.of(new TagTarget("index", "app_main", "orders", "")), null))
            .isInstanceOf(BulkTagRequestException.class);
        assertThatThrownBy(() ->
            service.attach("system", List.of(new TagTarget("table", "app_main", "", "")), null))
            .isInstanceOf(BulkTagRequestException.class);
    }

    /** A stated type that disagrees with the targets is a caller confusion. */
    @Test
    void aTypeThatDisagreesWithTheTargetsIsRefused() {
        assertThatThrownBy(() -> service.attach("system", List.of(MYSQL), "table"))
            .isInstanceOf(BulkTagRequestException.class);
    }
}
