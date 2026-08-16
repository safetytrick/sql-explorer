package com.github.safetytrick.sqlexplorer.analysis;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.WithItem;

/**
 * Common-table-expression bodies.
 *
 * <p>The column analyzers used to refuse a statement with a {@code WITH}
 * prefix outright. jsqlparser parses CTEs perfectly well — the refusal was
 * ours — so the whole of a CTE query was analysed as nothing: no criteria, no
 * join columns, no select columns, and an {@code analysisFailed} flag.
 *
 * <p>Each analyzer now walks the CTE bodies as well as the statement they feed.
 * The CTE <em>names</em> are deliberately not treated as tables: they are
 * aliases for a result set, not something on disk. jsqlparser's
 * {@code TablesNamesFinder} already omits them from the table list for the
 * same reason, so table extraction and column extraction agree.
 */
final class WithItems {

    private WithItems() {
    }

    /**
     * The names a statement's CTE definitions bind, lower-cased.
     *
     * <p>A reference through one of these — {@code tree.object_id} — names a
     * result set, not a column of a real table, so it must not be emitted as
     * a column reference. Doing so invents a table that is not in the schema
     * and that the statement's own table list (correctly) does not contain.
     *
     * <p>Resolving such a reference through to its underlying source column
     * would be better still, and is a separate piece of work: the projection
     * has to be matched positionally through a possibly-recursive UNION.
     */
    static Set<String> namesOf(Select select) {
        List<WithItem<?>> items = select.getWithItemsList();
        if (items == null || items.isEmpty()) {
            return Set.of();
        }
        return items.stream()
            .map(WithItem::getAliasName)
            .filter(Objects::nonNull)
            .map(name -> name.replace("`", "").toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());
    }

    /** Whether a resolved table name is really one of {@code cteNames}. */
    static boolean isCte(Set<String> cteNames, String tableName) {
        if (tableName == null || cteNames.isEmpty()) {
            return false;
        }
        return cteNames.contains(tableName.replace("`", "").toLowerCase(Locale.ROOT));
    }

    /**
     * The select bodies of a statement's CTE definitions, in declaration
     * order; empty when the statement has no {@code WITH} prefix.
     */
    static List<Select> bodiesOf(Select select) {
        List<WithItem<?>> items = select.getWithItemsList();
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        return items.stream()
            .map(WithItem::getSelect)
            .filter(Objects::nonNull)
            .map(Select.class::cast)
            .toList();
    }
}
