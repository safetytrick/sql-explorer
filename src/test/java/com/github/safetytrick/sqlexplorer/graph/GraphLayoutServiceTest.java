package com.github.safetytrick.sqlexplorer.graph;

import java.util.List;
import java.util.Optional;

import com.github.safetytrick.sqlexplorer.auth.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The hand-arranged grid layout: what a write accepts and what it does (sqlx-aoy.15). */
class GraphLayoutServiceTest {

    private GraphLayoutRepository repository;
    private GraphLayoutService service;

    @BeforeEach
    void setUp() {
        repository = mock(GraphLayoutRepository.class);
        when(repository.findByKindAndSchemaNameAndItemName(any(), any(), any())).thenReturn(Optional.empty());
        service = new GraphLayoutService(repository, mock(UserService.class));
    }

    @Test
    void savesASlotLowerCased() {
        service.save(List.of(new GraphLayoutItem("table", "App_Main", "Account_Site", 2, 1, false, null)));

        ArgumentCaptor<GraphLayoutEntry> saved = ArgumentCaptor.forClass(GraphLayoutEntry.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue()).extracting(GraphLayoutEntry::getKind, GraphLayoutEntry::getSchemaName,
                GraphLayoutEntry::getItemName, GraphLayoutEntry::getSlotCol, GraphLayoutEntry::getSlotRow)
            .containsExactly("table", "app_main", "account_site", 2, 1);
    }

    @Test
    void updatesTheRowAlreadyThereRatherThanAddingOne() {
        GraphLayoutEntry existing = new GraphLayoutEntry();
        existing.setId(7);
        when(repository.findByKindAndSchemaNameAndItemName("aggregate", "app_main", "invoice"))
            .thenReturn(Optional.of(existing));

        service.save(List.of(new GraphLayoutItem("aggregate", "app_main", "invoice", 0, 3, false, null)));

        verify(repository).save(existing);
        assertThat(existing.getSlotRow()).isEqualTo(3);
    }

    @Test
    void anItemWithNoSlotAndNoOverrideGoesBackToAutomaticPlacement() {
        GraphLayoutEntry existing = new GraphLayoutEntry();
        when(repository.findByKindAndSchemaNameAndItemName("table", "app_main", "site")).thenReturn(Optional.of(existing));

        service.save(List.of(new GraphLayoutItem("table", "app_main", "site", null, null, false, null)));

        verify(repository).delete(existing);
        verify(repository, never()).save(any());
    }

    @Test
    void keepsAnOverrideWithNoSlot_andANullParentMeansARootOfItsOwn() {
        service.save(List.of(new GraphLayoutItem("table", "app_main", "account_site", null, null, true, null)));

        ArgumentCaptor<GraphLayoutEntry> saved = ArgumentCaptor.forClass(GraphLayoutEntry.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().isAggregateOverride()).isTrue();
        assertThat(saved.getValue().getAggregateParent()).isNull();
    }

    /** The batch is checked whole first, so one bad item writes nothing at all. */
    @Test
    void oneBadItemRejectsTheWholeBatch() {
        List<GraphLayoutItem> batch = List.of(
            new GraphLayoutItem("table", "app_main", "account", 0, 0, false, null),
            new GraphLayoutItem("table", "app_main", "site", -1, 0, false, null));

        assertThatThrownBy(() -> service.save(batch)).isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("400").hasMessageContaining("out of range");
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsWhatCannotMeanAnything() {
        assertRejected(new GraphLayoutItem("column", "app_main", "x", 0, 0, false, null), "kind");
        assertRejected(new GraphLayoutItem("schema", "app_main", "x", 0, 0, false, null), "no name");
        assertRejected(new GraphLayoutItem("table", "app_main", "", 0, 0, false, null), "needs a name");
        assertRejected(new GraphLayoutItem("table", "app_main", "site", 0, null, false, null), "together");
        assertRejected(new GraphLayoutItem("aggregate", "app_main", "site", 0, 0, true, null), "only a table");
        assertRejected(new GraphLayoutItem("table", "app_main", "site", 0, 0, true, "Site"), "itself");
        assertRejected(new GraphLayoutItem("table", "app_main", "site", 0, 0, false, "account"), "only meaningful");
        assertRejected(new GraphLayoutItem("table", "x".repeat(65), "site", 0, 0, false, null), "longer than");
    }

    @Test
    void resetLeavesTheSchemasOwnCell() {
        service.reset("App_Main");
        verify(repository).deleteBySchemaNameAndKindIn("app_main", List.of("aggregate", "table"));
    }

    private static void assertRejected(GraphLayoutItem item, String why) {
        assertThatThrownBy(() -> GraphLayoutService.checked(item))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining(why);
    }
}
