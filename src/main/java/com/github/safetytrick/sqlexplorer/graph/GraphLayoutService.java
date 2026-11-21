package com.github.safetytrick.sqlexplorer.graph;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import com.github.safetytrick.sqlexplorer.auth.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class GraphLayoutService {

    static final Set<String> KINDS = Set.of("schema", "aggregate", "table");
    private static final int MYSQL_IDENTIFIER_MAX_LENGTH = 64;
    private static final int SLOT_LIMIT = 10_000;

    private final GraphLayoutRepository repository;
    private final UserService userService;

    GraphLayoutService(GraphLayoutRepository repository, UserService userService) {
        this.repository = repository;
        this.userService = userService;
    }

    List<GraphLayoutItem> all() {
        return repository.findAllByOrderBySchemaNameAscKindAscItemNameAsc().stream()
            .map(entry -> new GraphLayoutItem(entry.getKind(), entry.getSchemaName(), entry.getItemName(),
                entry.getSlotCol(), entry.getSlotRow(), entry.isAggregateOverride(), entry.getAggregateParent()))
            .toList();
    }

    @Transactional
    void save(List<GraphLayoutItem> items) {
        List<GraphLayoutItem> checked = items.stream().map(GraphLayoutService::checked).toList();
        for (GraphLayoutItem item : checked) {
            var existing = repository.findByKindAndSchemaNameAndItemName(item.kind(), item.schema(), item.name());
            if (item.col() == null && !item.override()) {
                existing.ifPresent(repository::delete);
                continue;
            }
            GraphLayoutEntry entry = existing.orElseGet(GraphLayoutEntry::new);
            entry.setKind(item.kind());
            entry.setSchemaName(item.schema());
            entry.setItemName(item.name());
            entry.setSlotCol(item.col());
            entry.setSlotRow(item.row());
            entry.setAggregateOverride(item.override());
            entry.setAggregateParent(item.override() ? item.parent() : null);
            entry.setUpdatedBy(userService.currentUser());
            repository.save(entry);
        }
    }

    @Transactional
    long reset(String schema) {
        String name = normalized(schema);
        long changed = repository.deleteBySchemaNameAndKindIn(name, List.of("aggregate"));
        changed += repository.deleteBySchemaNameAndKindAndAggregateOverrideFalse(name, "table");
        List<GraphLayoutEntry> overridden = repository.findBySchemaNameAndKindAndAggregateOverrideTrue(name, "table");
        for (GraphLayoutEntry entry : overridden) {
            entry.setSlotCol(null);
            entry.setSlotRow(null);
            entry.setUpdatedBy(userService.currentUser());
        }
        repository.saveAll(overridden);
        return changed + overridden.size();
    }

    static GraphLayoutItem checked(GraphLayoutItem item) {
        Objects.requireNonNull(item, "item");
        String kind = item.kind() == null ? "" : item.kind().toLowerCase(Locale.ROOT);
        if (!KINDS.contains(kind)) {
            throw bad("kind must be one of " + KINDS + ", not " + item.kind());
        }
        String schema = normalized(item.schema());
        if (schema.isEmpty()) {
            throw bad("schema is required");
        }
        String name = normalized(item.name());
        if (kind.equals("schema") != name.isEmpty()) {
            throw bad(kind.equals("schema") ? "a schema's cell has no name" : "a " + kind + " needs a name");
        }
        if ((item.col() == null) != (item.row() == null)) {
            throw bad("col and row are given together or not at all");
        }
        if (item.col() != null && (item.col() < 0 || item.row() < 0 || item.col() > SLOT_LIMIT || item.row() > SLOT_LIMIT)) {
            throw bad("slot out of range: " + item.col() + "," + item.row());
        }
        if (item.override() && !kind.equals("table")) {
            throw bad("only a table's aggregate can be overridden");
        }
        String parent = item.parent() == null ? null : normalized(item.parent());
        if (parent != null && (parent.isEmpty() || parent.equals(name))) {
            throw bad("a table cannot belong under " + (parent.isEmpty() ? "an empty name" : "itself"));
        }
        if (parent != null && !item.override()) {
            throw bad("a parent is only meaningful with override");
        }
        return new GraphLayoutItem(kind, schema, name, item.col(), item.row(), item.override(), parent);
    }

    private static String normalized(String name) {
        String value = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        if (value.length() > MYSQL_IDENTIFIER_MAX_LENGTH) {
            throw bad("name longer than " + MYSQL_IDENTIFIER_MAX_LENGTH + ": " + value);
        }
        return value;
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
