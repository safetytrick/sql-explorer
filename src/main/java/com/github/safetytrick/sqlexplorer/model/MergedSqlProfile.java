package com.github.safetytrick.sqlexplorer.model;

import java.util.List;
import java.util.stream.Collectors;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.avro.JoinColumns;
import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.avro.RuntimeMetrics;
import com.github.safetytrick.sqlexplorer.avro.QueryActivityStatus;
import com.github.safetytrick.sqlexplorer.avro.MergedChild;
import com.github.safetytrick.sqlexplorer.avro.RepeatedBlock;

/**
 * One query, seen through several Performance Schema digests.
 *
 * <h2>What a merge group is</h2>
 *
 * <p>Members share a {@code normalizedDigest} and differ in their MySQL
 * digest. That is the whole membership rule, and it is what makes the
 * aggregation policy below answerable rather than arbitrary: MySQL sees two
 * statements, our normalization sees one, and the group exists to put the two
 * MySQL rows back together.
 *
 * <p>Concretely, the normalization strips select-item aliases, so Hibernate
 * emitting {@code AS id92_0_} on one revision and {@code AS id93_0_} on the
 * next produces two MySQL digests for a query that is the same query. It also
 * collapses repeated blocks — see the caveat below, which is the one place
 * this gets interesting.
 *
 * <h2>Why the getters split the way they do</h2>
 *
 * <p>Two rules, and which applies follows from what the field describes:
 *
 * <ul>
 *   <li><b>Delegate to {@link #first()}</b> for anything that describes the
 *       <em>query</em>. Members are the same statement shape, so they agree:
 *       the tables are the same tables, the columns the same columns,
 *       {@code isDml} the same answer. Asking the representative is not a
 *       shortcut, it is the correct answer — and asking every member and
 *       reconciling them would invent a disagreement that cannot exist.</li>
 *   <li><b>Aggregate across the group</b> for anything that describes an
 *       <em>observation</em>. Execution counts, timings and first/last seen are
 *       per-MySQL-digest facts, and the group's answer is their sum or extreme.
 *       {@link #getSourcedFiles()} is the same shape: which files contributed
 *       is a fact about the group, not about the query.</li>
 * </ul>
 *
 * <p>{@code modifiedForAnalysis} unions rather than delegating, which looks
 * inconsistent with the first rule and is not: it records whether the
 * <em>input text</em> had to be rewritten before parsing, which is a property
 * of each captured row rather than of the query.
 *
 * <h2>The caveat: merged does not mean equally fast</h2>
 *
 * <p>Repeat collapsing means two statements with genuinely different runtime
 * behaviour can land in one group. Measured:
 *
 * <pre>
 *   WHERE id IN (?, ?, ?)          and  WHERE id IN (?)           → one digest
 *   INSERT … VALUES (?,?),(?,?),(?,?)  and  INSERT … VALUES (?,?) → one digest
 * </pre>
 *
 * <p>IN-list cardinality and batch size are first-order performance factors, so
 * a group's aggregate metrics can average a cheap statement with an expensive
 * one. The collapsed blocks are recorded as {@code repeatedBlocks} with their
 * occurrence counts, so the information is not lost — but it is per-member, and
 * nothing today shows its spread across a group.
 *
 * <p>Note what does <em>not</em> cause this, because it is the intuitive guess:
 * differing select lists do not merge. {@code SELECT a} and {@code SELECT a, b}
 * normalize distinctly, so a query covered by an index is not merged with one
 * that selects past it. Only aliases are stripped, and an alias cannot change a
 * plan. See {@code sqlx-6v1} for a digest variant that would surface
 * the cardinality spread.
 */
public class MergedSqlProfile extends SqlProfile {

    private List<SqlProfile> sourceList;
    private RuntimeMetrics merged;

    public MergedSqlProfile(List<SqlProfile> sourceList) {
        this.sourceList = sourceList;
    }

    public MergedSqlProfile() {
    }

    private SqlProfile first() {
        return sourceList.getFirst();
    }

    @Override
    public String getId() {
        return first().getDigest();
    }

    @Override
    public String getQuery() {
        return first().getQuery();
    }

    @Override
    public List<TableReference> getTables() {
        return first().getTables();
    }

    @Override
    public String getType() {
        return first().getType();
    }

    @Override
    public List<JoinColumns> getJoinColumns() {
        return first().getJoinColumns();
    }

    @Override
    public List<ColumnReference> getCriteriaColumns() {
        return first().getCriteriaColumns();
    }

    /*
     * These three were missing, and their absence was the bug: an override-per-
     * getter class silently inherits whatever it forgets. `MergedSqlProfile` is
     * constructed with `new`, never through the Avro builder, so an un-overridden
     * list field is the uninitialised superclass field — null, not the schema's
     * declared empty list. That is why exactly `selectColumns`, `groupByColumns`
     * and `schemas` came back null on merged profiles and nothing else did.
     */

    @Override
    public List<ColumnReference> getSelectColumns() {
        return first().getSelectColumns();
    }

    @Override
    public List<ColumnReference> getGroupByColumns() {
        return first().getGroupByColumns();
    }

    @Override
    public List<String> getSchemas() {
        return first().getSchemas();
    }

    /**
     * Whether the statement's tables span more than one schema.
     *
     * <p>Delegated to the representative rather than derived across the group,
     * because that is what the flag means: {@code SchemaAnalyzer} sets it from
     * the *statement's* table references, and every member of a merge group is
     * the same statement shape under one normalized digest. It also has to
     * agree with {@link #getSchemas()} and {@link #getSchemaName()}, which
     * already come from the representative — a union here would let a profile
     * claim multi-schema while listing a single schema.
     *
     * <p>Un-overridden it returned the uninitialised primitive, so every merged
     * profile reported false and was invisible to the multiSchema filter.
     */
    @Override
    public boolean getIsMultiSchema() {
        return first().getIsMultiSchema();
    }

    /** Same class of miss as the above; inherited it is always null. */
    @Override
    public String getSourceFileName() {
        return first().getSourceFileName();
    }

    @Override
    public String getGeneratedSource() {
        return first().getGeneratedSource();
    }

    @Override
    public boolean getTruncated() {
        return first().getTruncated();
    }

    @Override
    public boolean getAnalysisFailed() {
        return first().getAnalysisFailed();
    }

    @Override
    public boolean getAnalysisSkipped() {
        return first().getAnalysisSkipped();
    }

    @Override
    public boolean getModifiedForAnalysis() {
        return sourceList.stream().anyMatch(SqlProfile::getModifiedForAnalysis);
    }

    @Override
    public boolean getIsDml() {
        return first().getIsDml();
    }

    @Override
    public String getNormalizedDigest() {
        return first().getNormalizedDigest();
    }

    /**
     * The group's metrics, summed over the members that have any.
     *
     * <p>{@code runtimeMetrics} is nullable and the rest of the code says so —
     * the profile comparator orders a metric-less profile last, and
     * {@code recordMergedChild} guards on null. This did not: it handed the raw
     * list to {@code merged}, which dereferences every element, so one member
     * without metrics made the whole group throw. Filtering matches the
     * single-profile answer: no metrics anywhere means null, not zero.
     */
    @Override
    public RuntimeMetrics getRuntimeMetrics() {
        if (merged == null) {
            List<RuntimeMetrics> metrics = sourceList.stream()
                .map(SqlProfile::getRuntimeMetrics)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
            if (metrics.isEmpty()) {
                return null;
            }
            merged = RuntimeMetrics.merged(metrics);
        }
        return merged;
    }

    @Override
    public String getDigest() {
        return first().getDigest();
    }

    @Override
    public String getSchemaName() {
        return first().getSchemaName();
    }

    /**
     * The earliest line any member came from.
     *
     * <p>{@code lineNumber} is declared {@code int?}, so a profile built by
     * anything other than the performance-schema ingest — which always sets it
     * from the row's rank — carries null. Unboxing that inside
     * {@code mapToInt} threw NPE on group construction rather than returning a
     * wrong value, so it is filtered rather than defaulted: a group with no
     * line numbers keeps the existing empty-group fallback.
     */
    @Override
    public Integer getLineNumber() {
        return sourceList.stream().map(SqlProfile::getLineNumber)
            .filter(java.util.Objects::nonNull)
            .mapToInt(Integer::intValue).min()
            .orElse(Integer.MAX_VALUE);
    }

    @Override
    public List<JoinColumns> getMismatchedJoins() {
        return first().getMismatchedJoins();
    }

    @Override
    public String getAnalysisFailureReason() {
        return first().getAnalysisFailureReason();
    }

    /** Offsets index into the query text, which comes from {@link #first()}, so the repeats must too. */
    @Override
    public List<RepeatedBlock> getRepeatedBlocks() {
        return first().getRepeatedBlocks();
    }

    /**
     * The merge group is recorded on the representative at load time, so it
     * reads through {@link #first()} like the repeats do — this wrapper's own
     * Avro field is never populated.
     */
    @Override
    public List<MergedChild> getMergedChildren() {
        return first().getMergedChildren();
    }

    @Override
    public List<String> getSourcedFiles() {
        return sourceList.stream().map(SqlProfile::getSourcedFiles)
            .flatMap(List::stream).collect(Collectors.toList());
    }

    @Override
    public QueryActivityStatus getQueryActivityStatus() {
        QueryActivityStatus bottomStatus = QueryActivityStatus.UNKNOWN;
        for (SqlProfile sqlProfile : sourceList) {
            switch (sqlProfile.getQueryActivityStatus()) {
                case DORMANT:
                    bottomStatus = QueryActivityStatus.DORMANT;
                    break;
                case ACTIVE:
                    return QueryActivityStatus.ACTIVE;
                case UNKNOWN:
                    break;
            }
        }
        return bottomStatus;
    }

    @Override
    public List<String> getPerfDigests() {
        return sourceList.stream().flatMap(analyzedSql -> analyzedSql.getPerfDigests().stream())
                .distinct().collect(Collectors.toList());
    }
}
