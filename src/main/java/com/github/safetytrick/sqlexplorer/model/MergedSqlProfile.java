package com.github.safetytrick.sqlexplorer.model;

import java.util.List;
import java.util.stream.Collectors;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.avro.JoinColumns;
import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.avro.RuntimeMetrics;
import com.github.safetytrick.sqlexplorer.avro.QueryActivityStatus;

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
