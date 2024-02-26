package com.github.safetytrick.sqlexplorer.schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.github.safetytrick.sqlexplorer.model.ColumnReference;

public record ForeignKey(
    List<ColumnReference> from,
    List<ColumnReference> to) {

    public static class Builder {
        private final List<ColumnReference> from = new ArrayList<>();
        private final List<ColumnReference> to = new ArrayList<>();

        public Builder addFromAndTo(ColumnReference from, ColumnReference to) {
            this.from.add(from);
            this.to.add(to);
            return this;
        }
        public ForeignKey build() {
            if (from.size() != to.size()) {
                throw new IllegalStateException("cannot have ForeignKey with mismatched to and from");
            }
            if (from.isEmpty()) {
                throw new IllegalArgumentException("cannot build ForeignKey without to and from columns");
            }
            return new ForeignKey(Collections.unmodifiableList(from),
                Collections.unmodifiableList(to));
        }
    }
}
