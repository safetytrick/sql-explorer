package com.github.safetytrick.sqlexplorer.schema;

import java.util.List;

import com.github.safetytrick.sqlexplorer.analysis.NameNormalizer;
import com.github.safetytrick.sqlexplorer.model.ColumnReference;
import com.github.safetytrick.sqlexplorer.model.TableReference;
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
                             String create,
                             String remarks,
                             List<ForeignKey> foreignKeys,
                             List<TableIndex> indices,
                             List<TableColumn> columns) {

    public TableReference tableReference() {
        return new TableReference(schema, name);
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
        private String create;

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

        public Builder create(String create) {
            this.create = create;
            return this;
        }

        public TableStructure build() {
            return new TableStructure(schema, name, type, create, remarks, foreignKeys, indices, columns);
        }

    }

    public static TableStructure.Builder fromCreateTable(String create) throws JSQLParserException {
        return fromCreateTable(create, null);
    }

    public static TableStructure.Builder fromCreateTable(String create, String defaultSchema) throws JSQLParserException {
        Statement createStatement = CCJSqlParserUtil.parse(create);
        Builder builder = builder();

        createStatement.accept(new StatementVisitorAdapter() {
            @Override
            public void visit(CreateTable createTable) {
                Table table = createTable.getTable();
                if (table.getSchemaName() == null) {
                    table.setSchemaName(defaultSchema);
                }
                builder.name(table.getName())
                    .schema(table.getSchemaName())
                    .create(create);

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

                    // todo implement ... all of the rest of the attributes
                    TableColumn.Builder columnBuilder = TableColumn.builder();
                    columnBuilder.columnName(NameNormalizer.normalize(column.getColumnName()))
                        .typeName(dt.getDataType())
                        .characterSet(dt.getCharacterSet());

                    return columnBuilder.build();
                }).toList());
            }
        });

        return builder;
    }

    private static ForeignKey toForeignKey(Table table, ForeignKeyIndex fkIndex) {
        TableReference fromTable = new TableReference(table.getSchemaName(), table.getName());
        Table fkTable = fkIndex.getTable();
        if (fkTable.getSchemaName() == null) {
            fkTable.setSchemaName(table.getSchemaName());
        }

        TableReference toTable = new TableReference(fkTable.getSchemaName(), fkTable.getName());

        ForeignKey.Builder builder = new ForeignKey.Builder();
        for (int i = 0; i < fkIndex.getColumnsNames().size(); i++) {
            String fromColumn = fkIndex.getColumnsNames().get(i);
            String toColumn = fkIndex.getReferencedColumnNames().get(i);
            builder.addFromAndTo(new ColumnReference(fromTable, fromColumn),
                                 new ColumnReference(toTable, toColumn));
        }

        return builder.build();
    }
}
