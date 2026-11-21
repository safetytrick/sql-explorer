package com.github.safetytrick.sqlexplorer.schema;

import java.time.Instant;
import java.util.List;

public record SchemaSnapshot(int format, String environment, String cluster, String server, Instant readAt,
                             SchemaFingerprint fingerprint, List<TableStructure> tables) {

    public record Key(String environment, String cluster) { }
}
