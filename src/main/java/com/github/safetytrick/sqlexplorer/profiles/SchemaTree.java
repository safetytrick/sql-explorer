package com.github.safetytrick.sqlexplorer.profiles;

import java.time.LocalDateTime;
import java.util.List;

public record SchemaTree(List<SchemaNode> schemas) {

    public record SchemaNode(String name, List<TableNode> tables) { }

    public record TableNode(String name, Integer columnCount, int queryCount, boolean warn, boolean view,
                            boolean inSchema, LocalDateTime archivedLastSeen) { }
}
