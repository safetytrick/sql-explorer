package com.github.safetytrick.sqlexplorer.analysis;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.WithItem;

final class WithItems {

    private WithItems() {
    }

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

    static boolean isCte(Set<String> cteNames, String tableName) {
        if (tableName == null || cteNames.isEmpty()) {
            return false;
        }
        return cteNames.contains(tableName.replace("`", "").toLowerCase(Locale.ROOT));
    }

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
