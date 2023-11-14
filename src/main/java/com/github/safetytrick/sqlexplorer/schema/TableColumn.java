package com.github.safetytrick.sqlexplorer.schema;

import java.sql.JDBCType;

public class TableColumn {
    private final String schema;
    private final String table;
    private final String columnName;
    private final JDBCType type;
    private final String typeName;
    private final int columnSize;
    private final int decimalDigits;
    private final int radix;
    private final String remarks;
    private final String columnDefault;
    private final int octetLength;
    private final int ordinal;
    private final String isNullable;
    private final String isAutoIncrement;
    private final String isGenerated;
    private final String characterSet;
    private final String collation;

    private TableColumn(String schema,
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
                        String isNullable, String isAutoIncrement, String isGenerated, String characterSet, String collation) {
        this.schema = schema;
        this.table = table;
        this.columnName = columnName;
        this.type = type;
        this.typeName = typeName;
        this.columnSize = columnSize;
        this.decimalDigits = decimalDigits;
        this.radix = radix;
        this.remarks = remarks;
        this.columnDefault = columnDefault;
        this.octetLength = octetLength;
        this.ordinal = ordinal;
        this.isNullable = isNullable;
        this.isAutoIncrement = isAutoIncrement;
        this.isGenerated = isGenerated;
        this.characterSet = characterSet;
        this.collation = collation;
    }

    public String getSchema() {
        return schema;
    }

    public String getColumnName() {
        return columnName;
    }

    public JDBCType getType() {
        return type;
    }

    public String getTypeName() {
        return typeName;
    }

    public int getColumnSize() {
        return columnSize;
    }

    public int getDecimalDigits() {
        return decimalDigits;
    }

    public int getRadix() {
        return radix;
    }

    public String getRemarks() {
        return remarks;
    }

    public String getColumnDefault() {
        return columnDefault;
    }

    public int getOctetLength() {
        return octetLength;
    }

    public int getOrdinal() {
        return ordinal;
    }

    public String getIsNullable() {
        return isNullable;
    }

    public String getIsAutoIncrement() {
        return isAutoIncrement;
    }

    public String getIsGenerated() {
        return isGenerated;
    }

    public String getCharacterSet() {
        return characterSet;
    }

    public String getCollation() {
        return collation;
    }

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
