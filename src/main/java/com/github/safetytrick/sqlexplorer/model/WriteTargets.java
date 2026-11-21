package com.github.safetytrick.sqlexplorer.model;

import java.util.List;

import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;

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

    public static int writtenCount(SqlProfile profile) {
        List<TableReference> written = profile.getWriteTables();
        if (written != null && !written.isEmpty()) {
            return written.size();
        }
        return profile.getTables() == null ? 0 : profile.getTables().size();
    }
}
