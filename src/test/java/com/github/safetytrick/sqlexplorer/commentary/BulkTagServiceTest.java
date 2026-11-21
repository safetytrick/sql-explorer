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

class BulkTagServiceTest {

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

    @Test
    void attachesTheTagToEveryPath() {
        BulkTagService.BulkResult result = service.attach("system", List.of(MYSQL, SYS), null);

        assertThat(result.changed()).isEqualTo(2);
        assertThat(result.unchanged()).isZero();
        assertThat(saved()).extracting(Tag::getType, Tag::getSchemaName, Tag::getTableName)
            .containsExactly(tuple("schema", "mysql", ""), tuple("schema", "sys", ""));
    }

    @Test
    void aPathThatAlreadyCarriesTheTagIsLeftAlone() {
        lookupAnswers(List.of(tag("system", MYSQL)), List.of());

        BulkTagService.BulkResult result = service.attach("system", List.of(MYSQL, SYS), null);

        assertThat(result.changed()).isEqualTo(1);
        assertThat(result.unchanged()).isEqualTo(1);
        assertThat(saved()).extracting(Tag::getSchemaName).containsExactly("sys");
    }

    @Test
    void anotherTagOnThePathDoesNotCountAsAlreadyTagged() {
        lookupAnswers(List.of(tag("audit", MYSQL)));

        assertThat(service.attach("system", List.of(MYSQL), null).changed()).isEqualTo(1);
    }

    @Test
    void theTypeComesFromEachTarget() {
        service.attach("system", List.of(MYSQL,
            TagTarget.ofTable("mysql", "user"), TagTarget.ofQuery("abc123")), null);

        assertThat(saved()).extracting(Tag::getType).containsExactly("schema", "table", "query");
    }

    @Test
    void aTableNameContainingASeparatorDoesNotFailTheBatch() {
        BulkTagService.BulkResult result = service.attach("system",
            List.of(TagTarget.ofTable("probe", "a/b"), MYSQL), null);

        assertThat(result.changed()).isEqualTo(2);
        assertThat(saved()).extracting(Tag::getTableName).containsExactly("a/b", "");
    }

    @Test
    void repeatedPathsAreCountedOnce() {
        BulkTagService.BulkResult result = service.attach("system", List.of(MYSQL, SYS, MYSQL), null);

        assertThat(result.requested()).isEqualTo(2);
        assertThat(result.changed()).isEqualTo(2);
    }

    @Test
    void detachIsScopedToTheGivenPathsAndNotTheWholeTag() {
        Tag attached = tag("system", MYSQL);
        lookupAnswers(List.of(attached), List.of());

        BulkTagService.BulkResult result = service.detach("system", List.of(MYSQL, SYS));

        verify(tagRepository).deleteAllByIdInAndEnvironmentName(List.of(attached.getId()), ENV);
        verify(tagRepository, never()).deleteAllByNameAndEnvironmentName(any(), any());
        assertThat(result.changed()).isEqualTo(1);
        assertThat(result.unchanged()).isEqualTo(1);
    }

    @Test
    void detachingWhatIsNotThereChangesNothing() {
        BulkTagService.BulkResult result = service.detach("system", List.of(MYSQL));

        assertThat(result.changed()).isZero();
        assertThat(result.unchanged()).isEqualTo(1);
    }

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

    @Test
    void aTargetThatAddressesNothingIsRefused() {
        assertThatThrownBy(() ->
            service.attach("system", List.of(new TagTarget("index", "app_main", "orders", "")), null))
            .isInstanceOf(BulkTagRequestException.class);
        assertThatThrownBy(() ->
            service.attach("system", List.of(new TagTarget("table", "app_main", "", "")), null))
            .isInstanceOf(BulkTagRequestException.class);
    }

    @Test
    void aTypeThatDisagreesWithTheTargetsIsRefused() {
        assertThatThrownBy(() -> service.attach("system", List.of(MYSQL), "table"))
            .isInstanceOf(BulkTagRequestException.class);
    }
}
