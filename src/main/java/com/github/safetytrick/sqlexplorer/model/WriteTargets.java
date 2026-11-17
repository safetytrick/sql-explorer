package com.github.safetytrick.sqlexplorer.model;

import java.util.List;

import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;

/**
 * Whether a statement writes a given table, rather than only naming it
 * (sqlx-n5j.16).
 *
 * <p>A statement writes the table when {@link QueryKind} calls it a write and
 * the table is among its recorded {@code writeTables}. A statement with none
 * recorded — it did not parse, or was analysed before they were — still
 * counts against every table it names, which is all that is known about it.
 */
public final class WriteTargets {

    private WriteTargets() {
    }

    public static boolean writes(SqlProfile profile, TableReference table) {
        if (!QueryKind.of(profile).isWrite()) {
            return false;
        }
        List<TableReference> written = profile.getWriteTables();
        return written == null || written.isEmpty() || written.stream().anyMatch(table::equalsNormalized);
    }

    /** How many tables the statement writes: its recorded targets, else every table it names. */
    public static int writtenCount(SqlProfile profile) {
        List<TableReference> written = profile.getWriteTables();
        if (written != null && !written.isEmpty()) {
            return written.size();
        }
        return profile.getTables() == null ? 0 : profile.getTables().size();
    }
}
