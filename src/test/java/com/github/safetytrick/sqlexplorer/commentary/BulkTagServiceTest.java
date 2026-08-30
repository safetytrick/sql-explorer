package com.github.safetytrick.sqlexplorer.commentary;

import java.util.List;
import java.util.Map;

import com.github.safetytrick.sqlexplorer.auth.User;
import com.github.safetytrick.sqlexplorer.auth.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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

    private TagRepository tagRepository;
    private BulkTagService service;

    @BeforeEach
    void setUp() {
        tagRepository = mock(TagRepository.class);
        UserService userService = mock(UserService.class);
        when(userService.currentUser()).thenReturn(mock(User.class));
        when(tagRepository.findAllByPathInGroupedByPath(anyList())).thenReturn(Map.of());
        when(tagRepository.save(any(Tag.class))).thenAnswer(call -> call.getArgument(0));
        service = new BulkTagService(tagRepository, userService);
    }

    private static Tag tag(String name, String path) {
        Tag tag = new Tag();
        tag.setName(name);
        tag.setPath(path);
        return tag;
    }

    private List<Tag> saved() {
        ArgumentCaptor<Tag> captor = ArgumentCaptor.forClass(Tag.class);
        verify(tagRepository, atLeast(0)).save(captor.capture());
        return captor.getAllValues();
    }

    // ── attach ──

    @Test
    void attachesTheTagToEveryPath() {
        BulkTagService.BulkResult result = service.attach("system",
            List.of("/schema/mysql", "/schema/sys"), null);

        assertThat(result.changed()).isEqualTo(2);
        assertThat(result.unchanged()).isZero();
        assertThat(saved()).extracting(Tag::getPath)
            .containsExactly("/schema/mysql", "/schema/sys");
    }

    /**
     * Re-running over an overlapping set must not compound. Attaching is keyed
     * on (name, path), so a path already carrying the tag is left alone — and
     * "changed 0" is reported rather than treated as a failure.
     */
    @Test
    void aPathThatAlreadyCarriesTheTagIsLeftAlone() {
        when(tagRepository.findAllByPathInGroupedByPath(anyList())).thenReturn(
            Map.of("/schema/mysql", List.of(tag("system", "/schema/mysql"))));

        BulkTagService.BulkResult result = service.attach("system",
            List.of("/schema/mysql", "/schema/sys"), null);

        assertThat(result.changed()).isEqualTo(1);
        assertThat(result.unchanged()).isEqualTo(1);
        assertThat(saved()).extracting(Tag::getPath).containsExactly("/schema/sys");
    }

    /** A different tag on the same path is not the same association. */
    @Test
    void anotherTagOnThePathDoesNotCountAsAlreadyTagged() {
        when(tagRepository.findAllByPathInGroupedByPath(anyList())).thenReturn(
            Map.of("/schema/mysql", List.of(tag("audit", "/schema/mysql"))));

        assertThat(service.attach("system", List.of("/schema/mysql"), null).changed()).isEqualTo(1);
    }

    /** The kind is derived per path, as a single tag does. */
    @Test
    void theTypeIsDerivedFromEachPath() {
        service.attach("system", List.of("/schema/mysql", "/schema/mysql/table/user", "/query/abc123"), null);

        assertThat(saved()).extracting(Tag::getType).containsExactly("schema", "table", "query");
    }

    /**
     * The counts have to mean objects, not list entries — a caller can send the
     * same path twice, and reporting "tagged 3" for two schemas would be wrong.
     */
    @Test
    void repeatedPathsAreCountedOnce() {
        BulkTagService.BulkResult result = service.attach("system",
            List.of("/schema/mysql", "/schema/sys", "/schema/mysql"), null);

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
        when(tagRepository.findAllByPathInGroupedByPath(anyList())).thenReturn(
            Map.of("/schema/mysql", List.of(tag("system", "/schema/mysql"))));

        BulkTagService.BulkResult result = service.detach("system", List.of("/schema/mysql", "/schema/sys"));

        verify(tagRepository).deleteAllByNameAndPathIn("system", List.of("/schema/mysql", "/schema/sys"));
        verify(tagRepository, never()).deleteAllByName(any());
        assertThat(result.changed()).isEqualTo(1);
        assertThat(result.unchanged()).isEqualTo(1);
    }

    /** Detaching a tag nothing in the set carries is a no-op, not an error. */
    @Test
    void detachingWhatIsNotThereChangesNothing() {
        BulkTagService.BulkResult result = service.detach("system", List.of("/schema/mysql"));

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
        List<String> tooMany = java.util.stream.IntStream.rangeClosed(0, BulkTagService.MAX_PATHS)
            .mapToObj(i -> "/schema/s" + i).toList();

        assertThatThrownBy(() -> service.attach("system", tooMany, null))
            .isInstanceOf(BulkTagRequestException.class)
            .hasMessageContaining(String.valueOf(BulkTagService.MAX_PATHS))
            .hasMessageContaining("Nothing was changed");

        verify(tagRepository, never()).save(any(Tag.class));
    }

    @Test
    void aMalformedTagNameIsRefusedBeforeAnythingIsWritten() {
        assertThatThrownBy(() -> service.attach("not a tag", List.of("/schema/mysql"), null))
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

    /** A path with no derivable kind cannot be tagged blind. */
    @Test
    void aPathWithNoDerivableTypeIsRefused() {
        assertThatThrownBy(() -> service.attach("system", List.of("nonsense"), null))
            .isInstanceOf(BulkTagRequestException.class);
    }
}
