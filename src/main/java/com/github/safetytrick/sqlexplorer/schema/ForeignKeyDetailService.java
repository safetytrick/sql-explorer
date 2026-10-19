package com.github.safetytrick.sqlexplorer.schema;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import org.springframework.stereotype.Service;

/**
 * Foreign keys of a table in both directions ("out" = owned by the table,
 * "in" = other tables referencing it), with per-column type comparison.
 * Unlike join analysis — which tolerates joins across the integer family —
 * the comparison here is strict: an FK column pair whose type name, size or
 * charset differs is flagged.
 */
@Service
public class ForeignKeyDetailService {

    private final SqlSchemaService sqlSchemaService;

    public ForeignKeyDetailService(SqlSchemaService sqlSchemaService) {
        this.sqlSchemaService = sqlSchemaService;
    }

    public List<ForeignKeyDetail> getForeignKeys(TableReference table) {
        // column lookups cached for this call only — schemas are per-environment and refreshable
        Map<String, Map<String, TableColumn>> columnsByTable = new HashMap<>();
        List<ForeignKeyDetail> details = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        TableStructure structure = sqlSchemaService.getTable(table);
        if (structure != null) {
            for (ForeignKey fk : structure.foreignKeys()) {
                if (seen.add(constraintKey("out", table, fk))) {
                    TableReference other = fk.to().getFirst().getTable();
                    details.add(detail("out", fk, other, columnsByTable));
                }
            }
        }
        // incoming: scan every table's constraints for references to this table
        for (TableStructure owner : sqlSchemaService.getTables()) {
            for (ForeignKey fk : owner.foreignKeys()) {
                TableReference target = fk.to().getFirst().getTable();
                if (target != null && target.equalsNormalized(table)
                    && !owner.tableReference().equalsNormalized(table)
                    && seen.add(constraintKey("in", owner.tableReference(), fk))) {
                    details.add(detail("in", fk, owner.tableReference(), columnsByTable));
                }
            }
        }
        return details;
    }

    /**
     * Identifies a constraint across the servers of one environment, so it is
     * listed once — as the first server to define it did. getTables() holds
     * every server's copy of a table, and fifteen servers carrying the same
     * schema listed each referencing constraint fifteen times.
     *
     * <p>Keyed on the owning table as well as the name: MySQL constraint names
     * are unique per schema, so the same name on two tables is two
     * constraints. A constraint without a name cannot be matched to anything
     * and is never folded.
     */
    private static String constraintKey(String direction, TableReference owner, ForeignKey fk) {
        String name = fk.constraintName() == null ? "#" + System.identityHashCode(fk) : fk.constraintName();
        return (direction + "|" + owner.getNormalizedSchemaName() + "." + owner.getNormalizedTableName()
            + "|" + name).toLowerCase(Locale.ROOT);
    }

    private ForeignKeyDetail detail(String direction, ForeignKey fk, TableReference other,
                                    Map<String, Map<String, TableColumn>> columnsByTable) {
        List<ForeignKeyColumnDetail> columns = new ArrayList<>();
        for (int i = 0; i < fk.from().size(); i++) {
            ColumnReference from = fk.from().get(i);
            ColumnReference to = fk.to().get(i);
            TableColumn fromColumn = lookupColumn(from, columnsByTable);
            TableColumn toColumn = lookupColumn(to, columnsByTable);
            columns.add(new ForeignKeyColumnDetail(
                from.getColumn(), describe(fromColumn),
                to.getColumn(), describe(toColumn),
                isMismatch(fromColumn, toColumn)));
        }
        return new ForeignKeyDetail(direction, fk.constraintName(), fk.onUpdate(), fk.onDelete(),
            other == null ? null : other.getNormalizedSchemaName(),
            other == null ? null : other.getNormalizedTableName(),
            columns);
    }

    private TableColumn lookupColumn(ColumnReference reference, Map<String, Map<String, TableColumn>> columnsByTable) {
        if (reference == null || reference.getTable() == null) {
            return null;
        }
        TableReference table = reference.getTable();
        String tableKey = (table.getNormalizedSchemaName() + "." + table.getNormalizedTableName())
            .toLowerCase(Locale.ROOT);
        Map<String, TableColumn> columns = columnsByTable.computeIfAbsent(tableKey, key -> {
            TableStructure structure = sqlSchemaService.getTable(table);
            Map<String, TableColumn> byName = new HashMap<>();
            if (structure != null && structure.columns() != null) {
                structure.columns().forEach(column -> byName.put(column.columnName().toLowerCase(Locale.ROOT), column));
            }
            return byName;
        });
        String name = reference.getNormalizedColumn() != null ? reference.getNormalizedColumn() : reference.getColumn();
        return name == null ? null : columns.get(name.toLowerCase(Locale.ROOT));
    }

    private static String describe(TableColumn column) {
        return column == null ? null : column.typeName() + "(" + column.columnSize() + ")";
    }

    /** Strict FK-column comparison: type name, size, or charset differing is a mismatch. */
    public static boolean isMismatch(TableColumn from, TableColumn to) {
        if (from == null || to == null) {
            return false;
        }
        return !from.typeName().equalsIgnoreCase(to.typeName())
            || from.columnSize() != to.columnSize()
            || !Objects.equals(from.characterSet(), to.characterSet());
    }

    public record ForeignKeyDetail(String direction, String constraintName, String onUpdate, String onDelete,
                                   String otherSchema, String otherTable, List<ForeignKeyColumnDetail> columns) { }

    public record ForeignKeyColumnDetail(String fromColumn, String fromType,
                                         String toColumn, String toType, boolean typeMismatch) { }
}
