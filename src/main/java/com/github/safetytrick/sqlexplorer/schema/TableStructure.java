package com.github.safetytrick.sqlexplorer.schema;

import java.sql.JDBCType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.github.safetytrick.sqlexplorer.analysis.NameNormalizer;
import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.StatementVisitorAdapter;
import net.sf.jsqlparser.statement.create.table.ColDataType;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.table.ForeignKeyIndex;
import net.sf.jsqlparser.statement.create.view.CreateView;

public record TableStructure(String schema,
                             String name,
                             String type,
                             String remarks,
                             List<ForeignKey> foreignKeys,
                             List<TableIndex> indices,
                             List<TableColumn> columns,
                             /** Base tables a view selects from; null for base tables. */
                             List<TableReference> underlyingTables,
                             /** Per-column lineage for views; null for base tables. */
                             List<ViewColumn> viewColumns) {

    public TableReference tableReference() {
        return TableReference.newTableReference(schema, name);
    }

    /** Copy with the view's base tables and column lineage attached (see {@link ViewDefinitionService}). */
    public TableStructure withViewDefinition(List<TableReference> underlyingTables, List<ViewColumn> viewColumns) {
        return new TableStructure(schema, name, type, remarks, foreignKeys, indices, columns,
            underlyingTables, viewColumns);
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
        private List<TableReference> underlyingTables;
        private List<ViewColumn> viewColumns;

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

        public Builder underlyingTables(List<TableReference> underlyingTables) {
            this.underlyingTables = underlyingTables;
            return this;
        }

        public Builder viewColumns(List<ViewColumn> viewColumns) {
            this.viewColumns = viewColumns;
            return this;
        }

        public TableStructure build() {
            return new TableStructure(schema, name, type, remarks, foreignKeys, indices, columns,
                underlyingTables, viewColumns);
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
                String schemaName = NameNormalizer.normalize(table.getSchemaName());
                String tableName = NameNormalizer.normalize(table.getName());
                builder.name(tableName)
                    .schema(schemaName)
                    .type("TABLE");

                builder.indices(createTable.getIndexes().stream().map(index -> {

                    TableIndex.Builder indexBuilder = TableIndex.builder();
                    for (int i = 0; i < index.getColumnsNames().size(); i++) {
                        String columnName = index.getColumnsNames().get(i);
                        indexBuilder.addColumn((short) i, NameNormalizer.normalize(columnName));
                    }
                    // mirror the JDBC path: the primary key is named PRIMARY and is unique
                    String indexType = index.getType() == null ? "" : index.getType().toUpperCase(Locale.ROOT);
                    boolean primary = indexType.contains("PRIMARY");
                    indexBuilder.name(primary ? "PRIMARY" : NameNormalizer.normalize(index.getName()));
                    indexBuilder.unique(primary || indexType.contains("UNIQUE"));
                    indexBuilder.schema(schemaName);
                    return indexBuilder.build();
                }).toList());

                builder.foreignKeys(
                    createTable.getIndexes()
                        .stream()
                        .filter(index -> index instanceof ForeignKeyIndex)
                        .map(index -> toForeignKey(table, (ForeignKeyIndex) index))
                        .toList()
                );

                List<TableColumn> columns = new ArrayList<>();
                List<ColumnDefinition> definitions = createTable.getColumnDefinitions();
                for (int ordinal = 0; ordinal < definitions.size(); ordinal++) {
                    ColumnDefinition column = definitions.get(ordinal);
                    ColDataType dt = column.getColDataType();

                    // jsqlparser embeds the type arguments in the data type
                    // string, e.g. "BIGINT (19)" or "DECIMAL (10, 2)"; split them
                    // apart so the structure mirrors the JDBC path (base type name
                    // with a separate size/scale).
                    String rawType = dt.getDataType();
                    int[] sizeAndScale = typeArguments(rawType);
                    ColumnSpecs specs = ColumnSpecs.of(column.getColumnSpecs());

                    columns.add(TableColumn.builder()
                        .schema(schemaName)
                        .table(tableName)
                        .columnName(NameNormalizer.normalize(column.getColumnName()))
                        .type(jdbcType(baseTypeName(rawType)))
                        .typeName(baseTypeName(rawType))
                        .columnSize(sizeAndScale[0])
                        .decimalDigits(sizeAndScale[1])
                        .ordinal(ordinal + 1)
                        .isNullable(specs.notNull ? "NO" : "YES")
                        .isAutoIncrement(specs.autoIncrement ? "YES" : "NO")
                        .isGenerated(specs.generated ? "YES" : "NO")
                        .columnDefault(specs.columnDefault)
                        .characterSet(dt.getCharacterSet())
                        .build());
                }
                builder.columns(List.copyOf(columns));
                return null;
            }

            @Override
            public <S> Void visit(CreateView createView, S context) {
                Table view = createView.getView();
                if (view.getSchemaName() == null) {
                    view.setSchemaName(defaultSchema);
                }
                // columns are not derivable without resolving the view's select;
                // callers get the name/type/underlying tables and an empty structure.
                builder.name(NameNormalizer.normalize(view.getName()))
                    .schema(NameNormalizer.normalize(view.getSchemaName()))
                    .type("VIEW")
                    .indices(List.of())
                    .foreignKeys(List.of())
                    .columns(List.of())
                    .underlyingTables(createView.getSelect() == null
                        ? List.of()
                        : ViewDefinitionService.underlyingTables(createView.getSelect(), view.getSchemaName()));
                return null;
            }
        });

        return builder;
    }

    /** The NOT NULL / AUTO_INCREMENT / DEFAULT / GENERATED parts of a column definition's trailing specs. */
    private record ColumnSpecs(boolean notNull, boolean autoIncrement, boolean generated, String columnDefault) {

        static ColumnSpecs of(List<String> specs) {
            if (specs == null || specs.isEmpty()) {
                return new ColumnSpecs(false, false, false, null);
            }
            String joined = String.join(" ", specs).toUpperCase(Locale.ROOT);
            String columnDefault = null;
            for (int i = 0; i < specs.size() - 1; i++) {
                if ("DEFAULT".equalsIgnoreCase(specs.get(i))) {
                    columnDefault = unquote(specs.get(i + 1));
                    break;
                }
            }
            return new ColumnSpecs(
                joined.contains("NOT NULL"),
                joined.contains("AUTO_INCREMENT"),
                joined.contains("GENERATED") || joined.contains("VIRTUAL") || joined.contains("STORED"),
                columnDefault);
        }

        /** Mirror JDBC: a NULL default is null, string defaults come back unquoted. */
        private static String unquote(String value) {
            if (value == null || "NULL".equalsIgnoreCase(value)) {
                return null;
            }
            if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
                return value.substring(1, value.length() - 1);
            }
            return value;
        }
    }

    /**
     * The {@link JDBCType} the MySQL JDBC driver would report for a MySQL type
     * name, so DDL-built structures mirror introspected ones.
     */
    private static JDBCType jdbcType(String baseTypeName) {
        if (baseTypeName == null) {
            return JDBCType.OTHER;
        }
        return switch (baseTypeName.toUpperCase(Locale.ROOT)) {
            case "BIGINT" -> JDBCType.BIGINT;
            case "INT", "INTEGER", "MEDIUMINT" -> JDBCType.INTEGER;
            case "SMALLINT" -> JDBCType.SMALLINT;
            case "TINYINT" -> JDBCType.TINYINT;
            case "DECIMAL", "NUMERIC" -> JDBCType.DECIMAL;
            case "FLOAT" -> JDBCType.REAL;
            case "DOUBLE", "REAL" -> JDBCType.DOUBLE;
            case "BIT" -> JDBCType.BIT;
            case "BOOLEAN", "BOOL" -> JDBCType.BOOLEAN;
            case "CHAR", "ENUM", "SET" -> JDBCType.CHAR;
            case "VARCHAR" -> JDBCType.VARCHAR;
            case "TEXT", "TINYTEXT", "MEDIUMTEXT", "LONGTEXT", "JSON" -> JDBCType.LONGVARCHAR;
            case "DATE", "YEAR" -> JDBCType.DATE;
            case "DATETIME", "TIMESTAMP" -> JDBCType.TIMESTAMP;
            case "TIME" -> JDBCType.TIME;
            case "BINARY" -> JDBCType.BINARY;
            case "VARBINARY" -> JDBCType.VARBINARY;
            case "BLOB", "TINYBLOB", "MEDIUMBLOB", "LONGBLOB" -> JDBCType.LONGVARBINARY;
            default -> JDBCType.OTHER;
        };
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
