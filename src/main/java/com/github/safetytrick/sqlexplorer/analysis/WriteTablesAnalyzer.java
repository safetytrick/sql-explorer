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
