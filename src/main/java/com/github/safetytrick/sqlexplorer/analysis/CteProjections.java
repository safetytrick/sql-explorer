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

/**
 * Maps a reference <em>through</em> a CTE to the source column it stands for.
 *
 * <p>{@link WithItems} keeps CTE names out of the table list, which is right —
 * a CTE is a result set, not something on disk — but it left the references
 * pointing at one simply dropped. In the corpus's recursive example
 *
 * <pre>JOIN `tree` `parent` ON `child`.`parent_object` = `parent`.`object_id`</pre>
 *
 * that is really {@code node_link.parent_object} joined to
 * {@code node_link.object_id}: a genuine self-join, thrown away because
 * one side named the CTE.
 *
 * <h2>How the mapping works</h2>
 *
 * <p>Positionally. A CTE either declares its columns —
 * {@code WITH tree(entity_id, parent_id, …) AS …}, where position <i>i</i> of
 * the projection is that name — or inherits them from the body's select list.
 * Either way, {@code tree.object_id} is "whatever the body projects at the
 * position `object_id` occupies".
 *
 * <p>For a {@code UNION}, the mapping comes from the <b>anchor arm</b> — the
 * first one. The arms have to agree on shape for the union to be legal, and in
 * a recursive CTE the later arm references the CTE being defined, so it is the
 * anchor that reaches real tables. Reading the recursive arm would either
 * recurse forever or map a column to itself.
 *
 * <h2>What it deliberately cannot map</h2>
 *
 * <p>A CTE that projects an expression rather than a column —
 * {@code SELECT count(*) AS n} — has no source column for that position. The
 * result is partial by nature: those positions resolve to null and the caller
 * keeps its existing behaviour of dropping the reference. That is the honest
 * outcome; inventing a column would be worse than losing one.
 */
final class CteProjections {

    /** Nothing to map, for the common case of a statement with no {@code WITH}. */
    static final CteProjections EMPTY = new CteProjections(Map.of());

    /** cte name (lower-cased) → its column name (lower-cased) → the body's projected column. */
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

    /**
     * The source column {@code cteName.columnName} stands for, resolved to a
     * real table, or null when there is nothing to map it to.
     *
     * @param resolver used to resolve the source column within the CTE body,
     *                 which is where its {@code FROM} lives
     */
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

    /** The body a CTE's projection should be resolved against. */
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

    /**
     * Pair the CTE's column names with the anchor arm's projected expressions.
     *
     * <p>Names come from the declared list when there is one and from the
     * projection's own aliases and column names otherwise. Only positions whose
     * projection is a bare column are recorded — an expression has no source
     * column, and a position past the end of either list is a malformed
     * statement we simply do not map.
     */
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
            // anything else is an expression: no source column, left unmapped
        }
        return mapping;
    }

    /** {@code WITH tree(a, b, c) AS …} — the names the CTE binds, lower-cased. */
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

    /** With no declared list, a position is named by its alias, or by the column itself. */
    private static String inheritedName(SelectItem<?> selectItem) {
        if (selectItem.getAlias() != null) {
            return normalize(selectItem.getAlias().getName());
        }
        if (selectItem.getExpression() instanceof Column column) {
            return normalize(column.getColumnName());
        }
        return null;
    }

    /**
     * The first arm of the body. For a {@code UNION} that is the anchor; for a
     * plain body it is the body itself.
     */
    private static PlainSelect anchorArm(WithItem<?> item) {
        return anchorArm(item.getSelect());
    }

    /**
     * Unwraps parentheses on the way down. A CTE body arrives as a
     * {@link ParenthesedSelect} — the parentheses in {@code AS ( … )} are part
     * of the tree — so matching {@code PlainSelect} directly finds nothing.
     */
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
