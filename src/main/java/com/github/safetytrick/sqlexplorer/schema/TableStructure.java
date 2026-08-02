package com.github.safetytrick.sqlexplorer.schema;

import java.util.List;

import com.github.safetytrick.sqlexplorer.analysis.NameNormalizer;
import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.StatementVisitorAdapter;
import net.sf.jsqlparser.statement.create.table.ColDataType;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.table.ForeignKeyIndex;

public record TableStructure(String schema,
                             String name,
                             String type,
                             String remarks,
                             List<ForeignKey> foreignKeys,
                             List<TableIndex> indices,
                             List<TableColumn> columns) {

    public TableReference tableReference() {
        return TableReference.newTableReference(schema, name);
    }
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String schema;
        private String name;
        private String type;
        private String remarks;
        private List<ForeignKey> foreignKeys;
        private List<TableIndex> indices;
        private List<TableColumn> columns;

        public Builder schema(String schema) {
            this.schema = schema;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder type(String type) {
            this.type = type;
            return this;
        }

        public Builder remarks(String remarks) {
            this.remarks = remarks;
            return this;
        }
        public Builder foreignKeys(List<ForeignKey> foreignKeys) {
            this.foreignKeys = foreignKeys;
            return this;
        }

        public Builder indices(List<TableIndex> indices) {
            this.indices = indices;
            return this;
        }

        public Builder columns(List<TableColumn> columns) {
            this.columns = columns;
            return this;
        }

        public TableStructure build() {
            return new TableStructure(schema, name, type, remarks, foreignKeys, indices, columns);
        }

    }

    public static TableStructure.Builder fromCreateTable(String create) throws JSQLParserException {
        return fromCreateTable(create, null);
    }

    public static TableStructure.Builder fromCreateTable(String create, String defaultSchema) throws JSQLParserException {
        Statement createStatement = CCJSqlParserUtil.parse(create);
        Builder builder = builder();

        createStatement.accept(new StatementVisitorAdapter<Void>() {
            @Override
            public <S> Void visit(CreateTable createTable, S context) {
                Table table = createTable.getTable();
                if (table.getSchemaName() == null) {
                    table.setSchemaName(defaultSchema);
                }
                builder.name(table.getName())
                    .schema(table.getSchemaName());

                builder.indices(createTable.getIndexes().stream().map(index -> {

                    TableIndex.Builder indexBuilder = TableIndex.builder();
                    for (int i = 0; i < index.getColumnsNames().size(); i++) {
                        String columnName = index.getColumnsNames().get(i);
                        indexBuilder.addColumn((short) i, columnName);
                    }
                    indexBuilder.name(NameNormalizer.normalize(index.getName()));

                    // todo implement type, direction, unique, etc.
                    return indexBuilder.build();
                }).toList());

                builder.foreignKeys(
                    createTable.getIndexes()
                        .stream()
                        .filter(index -> index instanceof ForeignKeyIndex)
                        .map(index -> toForeignKey(table, (ForeignKeyIndex) index))
                        .toList()
                );

                builder.columns(createTable.getColumnDefinitions().stream().map(column -> {
                    ColDataType dt = column.getColDataType();

                    // jsqlparser embeds the type arguments in the data type
                    // string, e.g. "BIGINT (19)" or "DECIMAL (10, 2)"; split them
                    // apart so the structure mirrors the JDBC path (base type name
                    // with a separate size/scale).
                    String rawType = dt.getDataType();
                    int[] sizeAndScale = typeArguments(rawType);

                    // todo implement ... all of the rest of the attributes
                    TableColumn.Builder columnBuilder = TableColumn.builder();
                    columnBuilder.columnName(NameNormalizer.normalize(column.getColumnName()))
                        .typeName(baseTypeName(rawType))
                        .columnSize(sizeAndScale[0])
                        .decimalDigits(sizeAndScale[1])
                        .characterSet(dt.getCharacterSet());

                    return columnBuilder.build();
                }).toList());
                return null;
            }
        });

        return builder;
    }

    /** The base type name from a jsqlparser data type string, e.g. {@code "BIGINT (19)"} yields {@code "BIGINT"}. */
    private static String baseTypeName(String rawType) {
        if (rawType == null) {
            return null;
        }
        int open = rawType.indexOf('(');
        return (open < 0 ? rawType : rawType.substring(0, open)).trim();
    }

    /**
     * The numeric size and scale from a jsqlparser data type string:
     * {@code "INT (10)"} yields {@code [10, 0]}, {@code "DECIMAL (10, 2)"}
     * yields {@code [10, 2]}. Non-numeric arguments (such as {@code ENUM(...)}
     * values) and missing arguments yield 0, matching the default an
     * unspecified size would carry.
     */
    private static int[] typeArguments(String rawType) {
        int[] sizeAndScale = {0, 0};
        if (rawType == null) {
            return sizeAndScale;
        }
        int open = rawType.indexOf('(');
        int close = rawType.lastIndexOf(')');
        if (open < 0 || close <= open) {
            return sizeAndScale;
        }
        String[] arguments = rawType.substring(open + 1, close).split(",");
        for (int i = 0; i < arguments.length && i < sizeAndScale.length; i++) {
            try {
                sizeAndScale[i] = Integer.parseInt(arguments[i].trim());
            } catch (NumberFormatException e) {
                // non-numeric argument (e.g. ENUM values); leave as 0
            }
        }
        return sizeAndScale;
    }

    private static ForeignKey toForeignKey(Table table, ForeignKeyIndex fkIndex) {
        TableReference fromTable = TableReference.newTableReference(table.getSchemaName(), table.getName());
        Table fkTable = fkIndex.getTable();
        if (fkTable.getSchemaName() == null) {
            fkTable.setSchemaName(table.getSchemaName());
        }

        TableReference toTable = TableReference.newTableReference(fkTable.getSchemaName(), fkTable.getName());

        ForeignKey.Builder builder = new ForeignKey.Builder()
            .constraintName(fkIndex.getName())
            .rules(fkIndex.getOnUpdateReferenceOption(), fkIndex.getOnDeleteReferenceOption());
        for (int i = 0; i < fkIndex.getColumnsNames().size(); i++) {
            String fromColumn = fkIndex.getColumnsNames().get(i);
            String toColumn = fkIndex.getReferencedColumnNames().get(i);
            builder.addFromAndTo(ColumnReference.newColumnReference(fromTable, fromColumn),
                                 ColumnReference.newColumnReference(toTable, toColumn));
        }

        return builder.build();
    }
}
