package com.github.safetytrick.sqlexplorer.schema;

import java.util.List;
import java.util.stream.Collectors;

import com.github.safetytrick.sqlexplorer.cleanup.NameNormalizer;
import com.github.safetytrick.sqlexplorer.core.TableReference;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.StatementVisitorAdapter;
import net.sf.jsqlparser.statement.create.table.ColDataType;
import net.sf.jsqlparser.statement.create.table.CreateTable;

public class TableStructure {

    private final String schema;
    private final String name;
    private final String type;

    public String getCreate() {
        return create;
    }

    private final String create;
    private final String remarks;
    private final List<TableIndex> indices;
    private final List<TableColumn> columns;

    private TableStructure(String schema, String name, String type, String create, String remarks, List<TableIndex> indices, List<TableColumn> columns) {
        this.schema = schema;
        this.name = name;
        this.type = type;
        this.create = create;
        this.remarks = remarks;
        this.indices = indices;
        this.columns = columns;
    }

    public String getSchema() {
        return schema;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public String getRemarks() {
        return remarks;
    }

    public List<TableIndex> getIndices() {
        return indices;
    }

    public List<TableColumn> getColumns() {
        return columns;
    }

    public static Builder builder() {
        return new Builder();
    }

    public TableReference getTableReference() {
        return new TableReference(schema, name);
    }

    public static class Builder {

        private String schema;
        private String name;
        private String type;
        private String remarks;
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
            return new TableStructure(schema, name, type, create, remarks, indices, columns);
        }
    }

    public static TableStructure.Builder fromCreateTable(String create) throws JSQLParserException {
        Statement createStatement = CCJSqlParserUtil.parse(create);
        Builder builder = builder();

        createStatement.accept(new StatementVisitorAdapter() {
            @Override
            public void visit(CreateTable createTable) {
                Table table = createTable.getTable();
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
                }).collect(Collectors.toList()));

                builder.columns(createTable.getColumnDefinitions().stream().map(column -> {
                    ColDataType dt = column.getColDataType();


                    // todo implement ... all of the rest of the attributes
                    TableColumn.Builder columnBuilder = TableColumn.builder();
                    columnBuilder.columnName(NameNormalizer.normalize(column.getColumnName()))
                        .typeName(dt.getDataType())
                        .characterSet(dt.getCharacterSet())
                    ;

                    return columnBuilder.build();
                }).collect(Collectors.toList()));

            }
        });

        return builder;
    }
}
