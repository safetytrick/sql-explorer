package com.github.safetytrick.sqlexplorer.model;

import java.util.List;
import java.util.stream.Collectors;
import com.github.safetytrick.sqlexplorer.avro.IndexHint;
import com.github.safetytrick.sqlexplorer.avro.CriteriaComparison;
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

    public List<SqlProfile> members() {
        return List.copyOf(sourceList);
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

    @Override
    public List<ColumnReference> getSelectColumns() {
        return first().getSelectColumns();
    }

    @Override
    public List<ColumnReference> getGroupByColumns() {
        return first().getGroupByColumns();
    }

    @Override
    public List<IndexHint> getIndexHints() {
        return first().getIndexHints();
    }

    @Override
    public List<TableReference> getWriteTables() {
        return first().getWriteTables();
    }

    @Override
    public List<ColumnReference> getOrderByColumns() {
        return first().getOrderByColumns();
    }

    @Override
    public List<CriteriaComparison> getCriteriaComparisons() {
        return first().getCriteriaComparisons();
    }

    @Override
    public List<String> getSchemas() {
        return first().getSchemas();
    }

    @Override
    public boolean getIsMultiSchema() {
        return first().getIsMultiSchema();
    }

    @Override
    public String getSourceFileName() {
        return first().getSourceFileName();
    }

    @Override
    public String getGeneratedSource() {
        return first().getGeneratedSource();
    }

    @Override
    public List<com.github.safetytrick.sqlexplorer.avro.GeneratorCandidate> getGeneratorCandidates() {
        return first().getGeneratorCandidates();
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

    @Override
    public List<RepeatedBlock> getRepeatedBlocks() {
        return first().getRepeatedBlocks();
    }

    @Override
    public List<MergedChild> getMergedChildren() {
        return sourceList.stream()
            .skip(1)
            .map(MergedSqlProfile::describe)
            .collect(Collectors.toList());
    }

    private static MergedChild describe(SqlProfile member) {
        RuntimeMetrics metrics = member.getRuntimeMetrics();
        return MergedChild.newBuilder()
            .setDigest(metrics == null ? member.getDigest() : metrics.getDigest())
            .setRepeatedBlocks(member.getRepeatedBlocks())
            .setExecuted(metrics == null ? 0L : metrics.getExecuted())
            .setAverageTime(metrics == null ? java.math.BigDecimal.ZERO : metrics.getAverageTime())
            .build();
    }

    @Override
    public List<String> getSources() {
        return sourceList.stream().map(SqlProfile::getSources)
            .filter(java.util.Objects::nonNull)
            .flatMap(List::stream).distinct().collect(Collectors.toList());
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

    @Override
    public String getAnalyzerVersion() {
        return first().getAnalyzerVersion();
    }

    @Override
    public String getSessionSchema() {
        return first().getSessionSchema();
    }
}
