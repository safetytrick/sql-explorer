package com.github.safetytrick.sqlexplorer.schema;

import java.time.Instant;
import java.util.List;

/**
 * One cluster's schema as it was read, kept so the next start need not read it
 * again (docs/plans/cluster-schema-snapshots.md, sqlx-sqy.4).
 *
 * @param format      {@link SchemaSnapshotStore#FORMAT} when written; any other
 *                    value is unreadable and treated as no snapshot at all
 * @param server      the schemaFrom server it was read from
 * @param readAt      when it was read
 * @param fingerprint the schema's fingerprint at that read — the snapshot is
 *                    current exactly while a live fingerprint still equals it
 * @param tables      the loader's finished structures, view lineage attached
 */
public record SchemaSnapshot(int format, String environment, String cluster, String server, Instant readAt,
                             SchemaFingerprint fingerprint, List<TableStructure> tables) {

    /** Which cluster a snapshot belongs to: snapshots are per cluster, not per server. */
    public record Key(String environment, String cluster) { }
}
