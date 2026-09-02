package com.github.safetytrick.sqlexplorer.commentary;

import com.github.safetytrick.sqlexplorer.auth.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Detaching a tag asks which corpus it is working in.
 *
 * <p>Association ids are globally unique, so an unscoped delete could not remove
 * the wrong row by accident — but it would let an id belonging to one
 * environment be deleted while another was selected, and it was the last
 * destructive path in this package that did not ask (sqlx-8p0).
 */
class TagDeleteScopeTest {

    private static final String ENV = "regression";

    private TagRepository tagRepository;
    private TagsResource resource;

    @BeforeEach
    void setUp() {
        tagRepository = mock(TagRepository.class);
        CommentaryEnvironment environment = mock(CommentaryEnvironment.class);
        when(environment.name()).thenReturn(ENV);
        resource = new TagsResource(tagRepository, mock(UserService.class),
            mock(BulkTagService.class), environment, mock(TagTargetLookup.class));
    }

    @Test
    void deletingIsScopedToTheSelectedEnvironment() {
        when(tagRepository.deleteByIdAndEnvironmentName(7, ENV)).thenReturn(1L);

        resource.deleteTag(7);

        verify(tagRepository).deleteByIdAndEnvironmentName(7, ENV);
    }

    /**
     * A 404, not a silent success. The client removes the chip optimistically,
     * so reporting success for a row that is still there would leave the tag off
     * screen until the next load and then bring it back.
     */
    @Test
    void anIdThisEnvironmentDoesNotHaveIsReported() {
        when(tagRepository.deleteByIdAndEnvironmentName(7, ENV)).thenReturn(0L);

        assertThatThrownBy(() -> resource.deleteTag(7))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("404")
            .hasMessageContaining(ENV);
    }

    /** And nothing else is touched on the way past. */
    @Test
    void anUnscopedDeleteIsNeverIssued() {
        when(tagRepository.deleteByIdAndEnvironmentName(7, ENV)).thenReturn(1L);

        resource.deleteTag(7);

        assertThat(org.mockito.Mockito.mockingDetails(tagRepository).getInvocations())
            .allMatch(call -> call.getMethod().getName().equals("deleteByIdAndEnvironmentName"));
    }
}
