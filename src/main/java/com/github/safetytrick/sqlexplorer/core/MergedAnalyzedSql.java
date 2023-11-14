package com.github.safetytrick.sqlexplorer.core;

import java.util.List;
import java.util.stream.Collectors;

public class MergedAnalyzedSql extends AnalyzedSql {

    private final List<AnalyzedSql> sourceList;
    private RuntimeMetrics merged;

    public MergedAnalyzedSql(List<AnalyzedSql> sourceList) {
        this.sourceList = sourceList;
    }

    private AnalyzedSql first() {
        return sourceList.get(0);
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
    public boolean isTruncated() {
        return first().isTruncated();
    }

    @Override
    public boolean isAnalysisFailed() {
        return first().isAnalysisFailed();
    }

    @Override
    public boolean isAnalysisSkipped() {
        return first().isAnalysisSkipped();
    }

    @Override
    public boolean isModifiedForAnalysis() {
        return sourceList.stream().anyMatch(AnalyzedSql::isModifiedForAnalysis);
    }

    @Override
    public boolean isDml() {
        return first().isDml();
    }

    @Override
    public String getNormalizedDigest() {
        return first().getNormalizedDigest();
    }

    @Override
    public RuntimeMetrics getRuntimeMetrics() {
        if (merged == null) {
            merged = RuntimeMetrics.merged(sourceList.stream()
                                              .map(AnalyzedSql::getRuntimeMetrics)
                                              .collect(Collectors.toList()));
        }
        return merged;
    }

    @Override
    public String getDigest() {
        return first().getDigest();
    }

    @Override
    public String getSchema() {
        return first().getSchema();
    }

    @Override
    public int getLineNumber() {
        return sourceList.stream().mapToInt(AnalyzedSql::getLineNumber).min()
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
        return sourceList.stream().map(AnalyzedSql::getSourcedFiles)
            .flatMap(List::stream).collect(Collectors.toList());
    }

    @Override
    public QueryActivityStatus getQueryActivityStatus() {
        QueryActivityStatus bottomStatus = QueryActivityStatus.UNKNOWN;
        for (AnalyzedSql analyzedSql : sourceList) {
            switch (analyzedSql.getQueryActivityStatus()) {
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
