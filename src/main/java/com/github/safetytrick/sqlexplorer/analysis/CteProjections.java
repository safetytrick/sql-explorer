package com.github.safetytrick.sqlexplorer.analysis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.github.safetytrick.sqlexplorer.analysis.resolver.ColumnTableResolver;
import com.github.safetytrick.sqlexplorer.analysis.resolver.SchemaAwareTableAliasResolver;
import com.github.safetytrick.sqlexplorer.analysis.resolver.TableAliasResolver;
import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;

final class CteProjections {

    static final CteProjections EMPTY = new CteProjections(Map.of());

    private final Map<String, Map<String, Column>> byCte;

    private CteProjections(Map<String, Map<String, Column>> byCte) {
        this.byCte = byCte;
    }

    static CteProjections of(Select select) {
        List<WithItem<?>> items = select.getWithItemsList();
        if (items == null || items.isEmpty()) {
            return EMPTY;
        }

        Map<String, Map<String, Column>> byCte = new HashMap<>();
        for (WithItem<?> item : items) {
            String name = normalize(item.getAliasName());
            if (name == null) {
                continue;
            }
            PlainSelect anchor = anchorArm(item);
            if (anchor == null || anchor.getSelectItems() == null) {
                continue;
            }
            byCte.put(name, mapProjection(item, anchor));
        }
        return byCte.isEmpty() ? EMPTY : new CteProjections(byCte);
    }

    ColumnReference resolve(String cteName, String columnName, ColumnTableResolver resolver, Select body) {
        Map<String, Column> projection = byCte.get(normalize(cteName));
        if (projection == null) {
            return null;
        }
        Column source = projection.get(normalize(columnName));
        if (source == null) {
            return null;
        }

        TableAliasResolver aliases = new SchemaAwareTableAliasResolver(body, resolver);
        String table = aliases.resolveTableName(source);
        if (table == null) {
            return null;
        }
        return ColumnReference.fromComponents(null, table, source.getColumnName());
    }

    Select bodyOf(Select select, String cteName) {
        List<WithItem<?>> items = select.getWithItemsList();
        if (items == null) {
            return null;
        }
        String wanted = normalize(cteName);
        for (WithItem<?> item : items) {
            if (wanted != null && wanted.equals(normalize(item.getAliasName()))) {
                PlainSelect anchor = anchorArm(item);
                return anchor;
            }
        }
        return null;
    }

    boolean isEmpty() {
        return byCte.isEmpty();
    }

    private static Map<String, Column> mapProjection(WithItem<?> item, PlainSelect anchor) {
        List<String> names = declaredColumnNames(item);
        List<SelectItem<?>> projected = anchor.getSelectItems();

        Map<String, Column> mapping = new HashMap<>();
        for (int i = 0; i < projected.size(); i++) {
            SelectItem<?> selectItem = projected.get(i);

            String name = i < names.size() ? names.get(i) : inheritedName(selectItem);
            if (name == null) {
                continue;
            }
            if (selectItem.getExpression() instanceof Column column) {
                mapping.put(name, column);
            }
        }
        return mapping;
    }

    private static List<String> declaredColumnNames(WithItem<?> item) {
        Alias alias = item.getAlias();
        if (alias == null || alias.getAliasColumns() == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (Alias.AliasColumn column : alias.getAliasColumns()) {
            names.add(normalize(column.name));
        }
        return names;
    }

    private static String inheritedName(SelectItem<?> selectItem) {
        if (selectItem.getAlias() != null) {
            return normalize(selectItem.getAlias().getName());
        }
        if (selectItem.getExpression() instanceof Column column) {
            return normalize(column.getColumnName());
        }
        return null;
    }

    private static PlainSelect anchorArm(WithItem<?> item) {
        return anchorArm(item.getSelect());
    }

    private static PlainSelect anchorArm(Select body) {
        if (body == null) {
            return null;
        }
        if (body instanceof PlainSelect plain) {
            return plain;
        }
        if (body instanceof ParenthesedSelect parenthesed) {
            return anchorArm(parenthesed.getSelect());
        }
        if (body instanceof SetOperationList union && union.getSelects() != null) {
            for (Select arm : union.getSelects()) {
                PlainSelect plain = anchorArm(arm);
                if (plain != null) {
                    return plain;
                }
            }
        }
        return null;
    }

    private static String normalize(String name) {
        return name == null ? null : name.replace("`", "").toLowerCase(Locale.ROOT);
    }
}
