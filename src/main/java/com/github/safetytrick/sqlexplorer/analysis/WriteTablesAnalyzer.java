package com.github.safetytrick.sqlexplorer.analysis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.schema.SqlSchemaRepository;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.update.UpdateSet;
import net.sf.jsqlparser.statement.upsert.Upsert;
import org.springframework.stereotype.Component;

/**
 * The tables a statement writes, as opposed to every table it names
 * (sqlx-n5j.16). An {@code INSERT … SELECT}, an {@code UPDATE} with a join or
 * a {@code DELETE} with a subquery reads tables it does not change, and the
 * write rate used to charge them all.
 *
 * <ul>
 *   <li>INSERT and REPLACE: the target.</li>
 *   <li>UPDATE: the tables its SET columns belong to — a multi-table UPDATE
 *       can set several. A SET column without a qualifier belongs to the
 *       first table named, which is the only one in a single-table UPDATE
 *       and the one MySQL resolves an ambiguous column against first.</li>
 *   <li>DELETE: its target list when it names one ({@code DELETE a, b FROM
 *       …}), else its table.</li>
 * </ul>
 *
 * <p>Qualifiers may be aliases, so they are resolved against the tables the
 * statement names. Anything else writes nothing and records an empty list.
 */
@Component
class WriteTablesAnalyzer implements SqlAnalyzer {

    @Override
    public void analyze(Statement statement, SqlProfile.Builder builder, SqlSchemaRepository schema) {
        List<Table> written = switch (statement) {
            case Insert insert -> listOf(insert.getTable());
            case Upsert upsert -> listOf(upsert.getTable());
            case Update update -> updated(update);
            case Delete delete -> deleted(delete);
            default -> List.of();
        };
        Set<TableReference> references = new LinkedHashSet<>();
        for (Table table : written) {
            references.add(TableReference.newTableReference(table.getSchemaName(), table.getName()));
        }
        builder.setWriteTables(new ArrayList<>(references));
    }

    private static List<Table> listOf(Table table) {
        return table == null ? List.of() : List.of(table);
    }

    private static List<Table> updated(Update update) {
        Map<String, Table> named = new HashMap<>();
        Table first = update.getTable();
        name(named, first);
        joined(named, update.getStartJoins());
        joined(named, update.getJoins());
        if (update.getFromItem() instanceof Table from) {
            name(named, from);
        }
        List<Table> written = new ArrayList<>();
        if (update.getUpdateSets() != null) {
            for (UpdateSet set : update.getUpdateSets()) {
                if (set.getColumns() == null) {
                    continue;
                }
                for (Column column : set.getColumns()) {
                    Table qualifier = column.getTable();
                    String key = qualifier == null || qualifier.getName() == null ? null : key(qualifier.getName());
                    Table target = key == null ? first : named.getOrDefault(key, first);
                    if (target != null && !written.contains(target)) {
                        written.add(target);
                    }
                }
            }
        }
        return written.isEmpty() ? listOf(first) : written;
    }

    private static List<Table> deleted(Delete delete) {
        if (delete.getTables() == null || delete.getTables().isEmpty()) {
            return listOf(delete.getTable());
        }
        Map<String, Table> named = new HashMap<>();
        name(named, delete.getTable());
        joined(named, delete.getJoins());
        if (delete.getUsingList() != null) {
            delete.getUsingList().forEach(table -> name(named, table));
        }
        List<Table> written = new ArrayList<>();
        for (Table target : delete.getTables()) {
            Table resolved = named.getOrDefault(key(target.getName()), target);
            if (!written.contains(resolved)) {
                written.add(resolved);
            }
        }
        return written;
    }

    private static void joined(Map<String, Table> named, List<Join> joins) {
        if (joins == null) {
            return;
        }
        for (Join join : joins) {
            FromItem item = join.getRightItem();
            if (item instanceof Table table) {
                name(named, table);
            }
        }
    }

    /** By its alias and by its own name, so a qualifier written either way finds it. */
    private static void name(Map<String, Table> named, Table table) {
        if (table == null || table.getName() == null) {
            return;
        }
        named.putIfAbsent(key(table.getName()), table);
        if (table.getAlias() != null && table.getAlias().getName() != null) {
            named.put(key(table.getAlias().getName()), table);
        }
    }

    private static String key(String name) {
        return NameNormalizer.normalize(name).toLowerCase(Locale.ROOT);
    }
}
