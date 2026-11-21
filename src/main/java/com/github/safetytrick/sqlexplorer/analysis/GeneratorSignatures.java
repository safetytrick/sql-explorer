package com.github.safetytrick.sqlexplorer.analysis;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import com.github.safetytrick.sqlexplorer.avro.GeneratorCandidate;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.update.UpdateSet;
import net.sf.jsqlparser.util.TablesNamesFinder;

public final class GeneratorSignatures {

    public record Rule(String id, String description, Predicate<Shape> matches) {
    }

    public record Generator(String name, String description, List<Rule> rules) {
    }

    public record Shape(Statement statement, String text, Set<String> tableAliases, Set<String> selectAliases) {

        static Shape of(Statement statement, String text) {
            ShapeFinder finder = new ShapeFinder();
            finder.getTables(statement);
            return new Shape(statement, text == null ? statement.toString() : text,
                finder.tableAliases, finder.selectAliases);
        }

        boolean anyTableAlias(Pattern pattern) {
            return tableAliases.stream().anyMatch(alias -> pattern.matcher(alias).matches());
        }

        boolean anySelectAlias(Pattern pattern) {
            return selectAliases.stream().anyMatch(alias -> pattern.matcher(alias).matches());
        }
    }

    private static final Pattern DQL_TABLE_ALIAS = Pattern.compile("[a-z]\\d+_");
    private static final Pattern PERSISTER_ROOT_ALIAS = Pattern.compile("t0");
    private static final Pattern PERSISTER_COLUMN_ALIAS = Pattern.compile("[a-z][a-z0-9_]*_\\d+");
    private static final Pattern MANY_TO_MANY_LOADER = Pattern.compile(
        "(?is)\\bfrom\\s+[`\\w.]+\\s+(?:as\\s+)?`?c`?\\s+(?:inner\\s+)?join\\s+[`\\w.]+\\s+(?:as\\s+)?`?j`?"
            + "\\s+on\\s*\\(\\s*`?j`?\\s*\\.");
    private static final Pattern CRITERIA_ROOT_ALIAS = Pattern.compile("this_");
    private static final Pattern HIBERNATE_COLUMN_ALIAS = Pattern.compile("[a-z_]+\\d+_(?:\\d+_)+");
    private static final Pattern HQL_TABLE_ALIAS = Pattern.compile("[a-z][a-z_]+\\d+_");
    private static final Pattern HIBERNATE6_TABLE_ALIAS = Pattern.compile("[a-z]\\d+_\\d+");

    private static final Rule UOW_INSERT = new Rule("uow-insert",
        "INSERT INTO t (a, …) VALUES (?, …): one row, every value a parameter. Weak on its own: "
            + "every ORM writes this shape, and so does any code once literals become ?",
        GeneratorSignatures::isSimpleInsert);
    private static final Rule UOW_UPDATE = new Rule("uow-update",
        "UPDATE t SET a = ?, … WHERE id = ?: one unaliased table, every value a parameter, "
            + "equality on parameters only. Weak on its own, like uow-insert",
        GeneratorSignatures::isSimpleUpdate);

    public static final List<Generator> GENERATORS = List.of(
        new Generator("doctrine-orm", "Doctrine ORM (PHP)", List.of(
            new Rule("dql-walker-alias", "a table aliased like c0_ or t1_, as Doctrine's DQL walker writes",
                shape -> shape.anyTableAlias(DQL_TABLE_ALIAS)),
            new Rule("persister-alias", "a root table aliased t0 with columns aliased like id_1, "
                + "as Doctrine's entity persister writes",
                shape -> shape.anyTableAlias(PERSISTER_ROOT_ALIAS) && shape.anySelectAlias(PERSISTER_COLUMN_ALIAS)),
            new Rule("many-to-many-loader", "FROM x c JOIN y j ON (j. …, as Doctrine's many-to-many "
                + "collection loader writes",
                shape -> MANY_TO_MANY_LOADER.matcher(shape.text()).find()),
            UOW_INSERT,
            UOW_UPDATE)),
        new Generator("hibernate", "Hibernate ORM (Java)", List.of(
            new Rule("criteria-root-alias", "a table aliased this_, Hibernate Criteria's root",
                shape -> shape.anyTableAlias(CRITERIA_ROOT_ALIAS)),
            new Rule("select-alias", "columns aliased like id92_0_, as Hibernate 3–5 writes",
                shape -> shape.anySelectAlias(HIBERNATE_COLUMN_ALIAS)),
            new Rule("hql-table-alias", "a table aliased like account0_, as Hibernate 3–5 HQL writes",
                shape -> shape.anyTableAlias(HQL_TABLE_ALIAS)),
            new Rule("h6-table-alias", "a table aliased like a1_0, as Hibernate 6 writes",
                shape -> shape.anyTableAlias(HIBERNATE6_TABLE_ALIAS)),
            UOW_INSERT,
            UOW_UPDATE))
    );

    private GeneratorSignatures() {
    }

    public static List<GeneratorCandidate> match(Statement statement, String text) {
        Shape shape = Shape.of(statement, text);
        List<GeneratorCandidate> candidates = new ArrayList<>();
        for (Generator generator : GENERATORS) {
            for (Rule rule : generator.rules()) {
                if (rule.matches().test(shape)) {
                    candidates.add(new GeneratorCandidate(generator.name(), rule.id()));
                }
            }
        }
        return candidates;
    }

    public static boolean known(String generator) {
        return GENERATORS.stream().anyMatch(known -> known.name().equals(generator));
    }

    private static boolean isSimpleInsert(Shape shape) {
        if (!(shape.statement() instanceof Insert insert)) {
            return false;
        }
        if (insert.getColumns() == null || insert.getColumns().isEmpty()
            || insert.getValues() == null
            || insert.getDuplicateUpdateSets() != null && !insert.getDuplicateUpdateSets().isEmpty()
            || insert.getSetUpdateSets() != null && !insert.getSetUpdateSets().isEmpty()) {
            return false;
        }
        List<Expression> row = singleRow(insert.getValues().getExpressions());
        return row != null && row.size() == insert.getColumns().size()
            && row.stream().allMatch(JdbcParameter.class::isInstance);
    }

    private static List<Expression> singleRow(ExpressionList<?> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        if (values.size() == 1 && values.getFirst() instanceof ExpressionList<?> nested) {
            return new ArrayList<>(nested);
        }
        if (values.stream().anyMatch(ExpressionList.class::isInstance)) {
            return null;
        }
        return new ArrayList<>(values);
    }

    private static boolean isSimpleUpdate(Shape shape) {
        if (!(shape.statement() instanceof Update update)) {
            return false;
        }
        Table table = update.getTable();
        if (table == null || table.getAlias() != null
            || update.getJoins() != null && !update.getJoins().isEmpty()
            || update.getStartJoins() != null && !update.getStartJoins().isEmpty()
            || update.getFromItem() != null
            || update.getWhere() == null) {
            return false;
        }
        for (UpdateSet set : update.getUpdateSets()) {
            if (!set.getValues().stream().allMatch(JdbcParameter.class::isInstance)) {
                return false;
            }
        }
        return equalitiesOnParameters(update.getWhere());
    }

    private static boolean equalitiesOnParameters(Expression where) {
        if (where instanceof AndExpression and) {
            return equalitiesOnParameters(and.getLeftExpression()) && equalitiesOnParameters(and.getRightExpression());
        }
        return where instanceof EqualsTo equals
            && equals.getLeftExpression() instanceof Column
            && equals.getRightExpression() instanceof JdbcParameter;
    }

    private static final class ShapeFinder extends TablesNamesFinder<Void> {
        final Set<String> tableAliases = new HashSet<>();
        final Set<String> selectAliases = new HashSet<>();

        @Override
        public <S> Void visit(Table table, S context) {
            super.visit(table, context);
            if (table.getAlias() != null) {
                tableAliases.add(plain(table.getAlias().getName()));
            }
            return null;
        }

        @Override
        public <S> Void visit(PlainSelect plainSelect, S context) {
            if (plainSelect.getSelectItems() != null) {
                for (SelectItem<?> item : plainSelect.getSelectItems()) {
                    if (item.getAlias() != null) {
                        selectAliases.add(plain(item.getAlias().getName()));
                    }
                }
            }
            return super.visit(plainSelect, context);
        }

        private static String plain(String name) {
            return name.replace("`", "").replace("\"", "").toLowerCase(Locale.ROOT);
        }
    }
}
