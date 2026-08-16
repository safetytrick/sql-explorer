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

    @Override
    public RuntimeMetrics getRuntimeMetrics() {
        if (merged == null) {
            merged = RuntimeMetrics.merged(sourceList.stream()
                                              .map(SqlProfile::getRuntimeMetrics)
                                              .collect(Collectors.toList()));
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

    @Override
    public Integer getLineNumber() {
        return sourceList.stream().mapToInt(SqlProfile::getLineNumber).min()
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
