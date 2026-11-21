package com.github.safetytrick.sqlexplorer.schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.github.safetytrick.sqlexplorer.avro.ColumnReference;

public record ForeignKey(
    List<ColumnReference> from,
    List<ColumnReference> to,
    String constraintName,
    String onUpdate,
    String onDelete) {

    public static class Builder {
        private final List<ColumnReference> from = new ArrayList<>();
        private final List<ColumnReference> to = new ArrayList<>();
        private String constraintName;
        private String onUpdate;
        private String onDelete;

        public Builder addFromAndTo(ColumnReference from, ColumnReference to) {
            this.from.add(from);
            this.to.add(to);
            return this;
        }

        public Builder constraintName(String constraintName) {
            this.constraintName = constraintName;
            return this;
        }

        public Builder rules(String onUpdate, String onDelete) {
            this.onUpdate = onUpdate;
            this.onDelete = onDelete;
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
                Collections.unmodifiableList(to),
                constraintName, onUpdate, onDelete);
        }
    }
}
