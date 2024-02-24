package com.github.safetytrick.sqlexplorer.schema;

import java.sql.JDBCType;

public record TableColumn(String schema,
                          String table,
                          String columnName,
                          JDBCType type,
                          String typeName,
                          int columnSize,
                          int decimalDigits,
                          int radix,
                          String remarks,
                          String columnDefault,
                          int octetLength,
                          int ordinal,
                          String isNullable,
                          String isAutoIncrement,
                          String isGenerated,
                          String characterSet,
                          String collation) {

    @Override
    public String toString() {
        return schema + "." + table + "." + columnName;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String schema;
        private String table;
        private String columnName;
        private JDBCType type;
        private String typeName;
        private int columnSize;
        private int decimalDigits;
        private int radix;
        private String remarks;
        private String columnDefault;
        private int octetLength;
        private int ordinal;
        private String isNullable;
        private String isAutoIncrement;
        private String isGenerated;
        private String characterSet;
        private String collation;

        private Builder() {}

        public Builder schema(String schema) {
            this.schema = schema;
            return this;
        }

        public Builder table(String table) {
            this.table = table;
            return this;
        }

        public Builder columnName(String columnName) {
            this.columnName = columnName == null ? null : columnName.toLowerCase();
            return this;
        }

        public Builder type(JDBCType type) {
            this.type = type;
            return this;
        }

        public Builder typeName(String typeName) {
            this.typeName = typeName;
            return this;
        }

        public Builder columnSize(int columnSize) {
            this.columnSize = columnSize;
            return this;
        }

        public Builder decimalDigits(int decimalDigits) {
            this.decimalDigits = decimalDigits;
            return this;
        }

        public Builder radix(int radix) {
            this.radix = radix;
            return this;
        }

        public Builder remarks(String remarks) {
            this.remarks = remarks;
            return this;
        }

        public Builder columnDefault(String columnDefault) {
            this.columnDefault = columnDefault;
            return this;
        }

        public Builder octetLength(int octetLength) {
            this.octetLength = octetLength;
            return this;
        }

        public Builder ordinal(int ordinal) {
            this.ordinal = ordinal;
            return this;
        }

        public Builder isNullable(String isNullable) {
            this.isNullable = isNullable;
            return this;
        }

        public Builder isAutoIncrement(String isAutoIncrement) {
            this.isAutoIncrement = isAutoIncrement;
            return this;
        }

        public Builder isGenerated(String isGenerated) {
            this.isGenerated = isGenerated;
            return this;
        }

        public Builder characterSet(String characterSet) {
            this.characterSet = characterSet;
            return this;
        }

        public Builder collation(String collation) {
            this.collation = collation;
            return this;
        }

        public TableColumn build() {
            return new TableColumn(schema, table, columnName, type, typeName, columnSize, decimalDigits,
                                   radix, remarks, columnDefault, octetLength, ordinal, isNullable, isAutoIncrement, isGenerated, characterSet, collation);
        }


    }
}
