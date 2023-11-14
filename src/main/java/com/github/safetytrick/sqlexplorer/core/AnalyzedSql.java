package com.github.safetytrick.sqlexplorer.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class AnalyzedSql implements Comparable<AnalyzedSql> {
    private final String id;
    private final String query;
    private final List<TableReference> tables;
    private final String type;
    private final List<JoinColumns> joinColumns;
    private final List<ColumnReference> criteriaColumns;
    private final String generatedSource;
    private final boolean truncated;
    private final boolean analysisFailed;
    private final boolean analysisSkipped;
    private final boolean isDml;
    private final RuntimeMetrics runtimeMetrics;
    private final String digest;
    private final String normalizedDigest;
    private final String schema;
    private final List<JoinColumns> mismatchedJoins;
    private final int lineNumber;
    private final String analysisFailureReason;
    private final List<String> sourcedFiles;
    private final QueryActivityStatus queryActivityStatus;
    private final boolean modifiedForAnalysis;
    private final boolean isMultiSchema;

    protected AnalyzedSql() {
        this.id = null;
        this.query = null;
        this.tables = null;
        this.type = null;
        this.joinColumns = null;
        this.criteriaColumns = null;
        this.generatedSource = null;
        this.truncated = false;
        this.analysisFailed = false;
        this.isDml = false;
        this.runtimeMetrics = null;
        this.digest = null;
        this.normalizedDigest = null;
        this.schema = null;
        this.mismatchedJoins = null;
        this.lineNumber = -1;
        this.analysisFailureReason = null;
        this.sourcedFiles = null;
        this.queryActivityStatus = null;
        this.analysisSkipped = false;
        this.modifiedForAnalysis = false;
        this.isMultiSchema = false;
    }

    private AnalyzedSql(String id,
                        String query,
                        List<TableReference> tables,
                        String type,
                        List<JoinColumns> joinColumns,
                        List<ColumnReference> criteriaColumns,
                        String generatedSource,
                        boolean truncated,
                        boolean analysisFailed,
                        boolean analysisSkipped,
                        boolean isDml,
                        RuntimeMetrics runtimeMetrics,
                        String digest,
                        String normalizedDigest,
                        String schema,
                        List<JoinColumns> mismatchedJoins,
                        int lineNumber,
                        String analysisFailureReason,
                        List<String> sourcedFiles,
                        QueryActivityStatus queryActivityStatus,
                        boolean modifiedForAnalysis,
                        boolean isMultiSchema) {
        this.id = id;
        this.query = query;
        this.tables = tables;
        this.type = type;
        this.joinColumns = joinColumns;
        this.criteriaColumns = criteriaColumns;
        this.generatedSource = generatedSource;
        this.truncated = truncated;
        this.analysisFailed = analysisFailed;
        this.analysisSkipped = analysisSkipped;
        this.isDml = isDml;
        this.runtimeMetrics = runtimeMetrics;
        this.digest = digest;
        this.normalizedDigest = normalizedDigest;
        this.schema = schema;
        this.mismatchedJoins = mismatchedJoins;
        this.lineNumber = lineNumber;
        this.analysisFailureReason = analysisFailureReason;
        this.sourcedFiles = sourcedFiles;
        this.queryActivityStatus = queryActivityStatus;
        this.modifiedForAnalysis = modifiedForAnalysis;
        this.isMultiSchema = isMultiSchema;
    }

    public String getId() {
        return id;
    }

    public String getQuery() {
        return query;
    }

    public List<TableReference> getTables() {
        return tables;
    }

    public String getType() {
        return type;
    }

    public List<JoinColumns> getJoinColumns() {
        return joinColumns;
    }

    public List<ColumnReference> getCriteriaColumns() {
        return criteriaColumns;
    }

    public String getGeneratedSource() {
        return generatedSource;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public boolean isAnalysisFailed() {
        return analysisFailed;
    }

    public RuntimeMetrics getRuntimeMetrics() {
        return runtimeMetrics;
    }

    public String getDigest() {
        return digest;
    }

    public String getSchema() {
        return schema;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<JoinColumns> getMismatchedJoins() {
        return mismatchedJoins;
    }

    public String getAnalysisFailureReason() {
        return analysisFailureReason;
    }

    public List<String> getSourcedFiles() {
        return sourcedFiles;
    }

    public QueryActivityStatus getQueryActivityStatus() {
        return queryActivityStatus;
    }

    public List<String> getPerfDigests() {
        if (runtimeMetrics != null && runtimeMetrics.getDigest() != null) {
            return Collections.singletonList(runtimeMetrics.getDigest());
        }
        return Collections.emptyList();
    }

    public boolean isDml() {
        return isDml;
    }

    public String getNormalizedDigest() {
        return normalizedDigest;
    }


    public boolean isAnalysisSkipped() {
        return analysisSkipped;
    }

    public boolean isModifiedForAnalysis() {
        return modifiedForAnalysis;
    }

    public boolean isMultiSchema() {
        return isMultiSchema;
    }

    public boolean constrainedBy(ColumnReference columnReference) {
        if (joinColumns != null && joinColumns.stream().anyMatch(join -> join.contains(columnReference))) {
            return true;
        }
        // todo migrate criteriaColumns to a ColumnReference
        return criteriaColumns != null && criteriaColumns.contains(columnReference);
    }

    /**
     * Note: this class has a natural ordering that is inconsistent with equals.
     *
     * @param other the object to be compared.
     * @return comparison based on relevancy of the other sql (query status and then total time)
     */
    @Override
    public int compareTo(AnalyzedSql other) {
        if (this == other) {
            return 0;
        }
        int result = getQueryActivityStatus().compareTo(other.getQueryActivityStatus());
        if (result != 0) {
            return result;
        }

        if (getRuntimeMetrics() == null) {
            return other.getRuntimeMetrics() == null ? 0 : -1;
        } else if (other.getRuntimeMetrics() == null) {
            return 1;
        }

        // reversed
        return other.getRuntimeMetrics().getTotalTime().compareTo(getRuntimeMetrics().getTotalTime());
    }

    public static class Builder {

        private Builder() {
        }
        private final View view = new View();
        private String id;
        private String query;
        private List<TableReference> tables = new ArrayList<>();
        private String type;
        private List<JoinColumns> joinColumns = new ArrayList<>();
        private List<ColumnReference> criteriaColumns = new ArrayList<>();
        private String generatedSource;
        private boolean truncated;
        private boolean analysisFailed;
        private boolean analysisSkipped;
        private boolean isDml;
        private RuntimeMetrics runtimeMetrics;
        private String digest;
        private String normalizedDigest;
        private String schema;
        private List<String> schemas = new ArrayList<>();
        private List<JoinColumns> mismatchedJoins = new ArrayList<>();
        private int lineNumber;
        private String analysisFailureReason;
        private List<String> sourcedFiles = new ArrayList<>();
        private QueryActivityStatus queryActivityStatus = QueryActivityStatus.UNKNOWN;
        private boolean isMultiSchema;
        private boolean isSchemaQualified;
        private boolean modifiedForAnalysis;

        public Builder id(String id) {
            this.id = id;
            return this;
        }
        public Builder query(String query) {
            this.query = query;
            return this;
        }
        public Builder tables(List<TableReference> tables) {
            if (tables != null) {
                for (TableReference table : tables) {
                    if (!this.tables.contains(table)) {
                        if (table.getSchema() != null) {
                            if (!schemas.contains(table.getNormalizedSchema())) {
                                schemas.add(table.getNormalizedSchema());
                            }
                        }
                        this.tables.add(table);
                    }
                }
            }
            return this;
        }
        public Builder type(String type) {
            this.type = type;
            return this;
        }
        public Builder joinColumns(List<JoinColumns> joinColumns) {
            if (joinColumns != null) {
                for (JoinColumns joinPair : joinColumns) {
                    if (!this.joinColumns.contains(joinPair)) {
                        this.joinColumns.add(joinPair);
                    }
                }

            }
            return this;
        }


        public Builder joinPair(String leftColumn, String rightColumn) {
            leftColumn = transformName(leftColumn);
            rightColumn = transformName(rightColumn);
            JoinColumns joinPair = JoinColumns.fromReferences(leftColumn, rightColumn);
            if (!joinColumns.contains(joinPair)) {
                joinColumns.add(joinPair);
            }
            return this;
        }

        public Builder criteriaColumns(List<ColumnReference> criteriaColumns) {
            if (criteriaColumns != null) {
                for (ColumnReference criteriaColumn : criteriaColumns) {
                    criteriaColumn(criteriaColumn);
                }
                
            }
            return this;
        }

        public Builder criteriaColumn(ColumnReference columnReference) {
            if (!this.criteriaColumns.contains(columnReference)) {
                this.criteriaColumns.add(columnReference);
            }
            return this;
        }

        public Builder generatedSource(String generatedSource) {
            this.generatedSource = generatedSource;
            return this;
        }

        public Builder truncated(boolean truncated) {
            this.truncated = truncated;
            return this;
        }

        public Builder analysisFailed(boolean analysisFailed) {
            this.analysisFailed = analysisFailed;
            return this;
        }


        public Builder analysisSkipped(boolean analysisSkipped) {
            this.analysisSkipped = analysisSkipped;
            return this;
        }

        public Builder failureReason(String reason) {
            analysisFailed(true);
            this.analysisFailureReason = reason;
            return this;
        }
        public Builder runtimeMetrics(RuntimeMetrics runtimeMetrics) {
            this.runtimeMetrics = runtimeMetrics;
            if (runtimeMetrics != null) {
                digest(runtimeMetrics.getDigest());
            }
            return this;
        }

        public Builder schema(String schema) {
            this.schema = schema;
            return this;
        }

        public Builder digest(String digest) {
            this.digest = digest;
            return this;
        }

        public Builder normalizedDigest(String normalizedDigest) {
            this.normalizedDigest = normalizedDigest;
            if (normalizedDigest != null) {
                this.digest = normalizedDigest;
            }
            return this;
        }

        public Builder mismatchedJoins(List<JoinColumns> mismatchedJoins) {
            this.mismatchedJoins.addAll(mismatchedJoins);
            return this;
        }

        public Builder lineNumber(int lineNumber) {
            this.lineNumber = lineNumber;
            return this;
        }

        public Builder sourceFileName(String sourceFileName) {
            this.sourcedFiles.add(sourceFileName);
            return this;
        }

        public Builder queryActivityStatus(QueryActivityStatus queryActivityStatus) {
            this.queryActivityStatus = queryActivityStatus;
            return this;
        }

        public Builder isDml(boolean isDml) {
            this.isDml = isDml;
            return this;
        }


        public Builder isMultiSchema(boolean isMultiSchema) {
            this.isMultiSchema = isMultiSchema;
            return this;
        }

        public Builder isSchemaQualified(boolean isSchemaQualified) {
            this.isSchemaQualified = isSchemaQualified;
            return this;
        }

        private List<String> transformNames(List<String> ids) {
            return ids.stream()
                .map(this::transformName)
                .collect(Collectors.toList());
        }

        private String transformName(String name) {
            return name.replace("`", "");
        }

        public AnalyzedSql build() {
            if (id == null) {
                id = digest;
            }

            if (!isMultiSchema && isSchemaQualified) {
                this.tables = this.tables.stream().map(tableRef -> tableRef.withDefaultSchema(schema))
                    .distinct()
                    .collect(Collectors.toList());
            }

            List<JoinColumns> mismatchedJoins = new ArrayList<>(this.mismatchedJoins.size());
            for (JoinColumns join : this.mismatchedJoins) {
                mismatchedJoins.add(join.withDefaultSchema(schema));
            }

            List<ColumnReference> criteriaColumns = new ArrayList<>(this.criteriaColumns.size());
            for (ColumnReference criteriaColumn : this.criteriaColumns) {
                criteriaColumns.add(criteriaColumn.withDefaultSchema(schema));
            }

            List<JoinColumns> joinColumns = new ArrayList<>(this.joinColumns.size());
            for (JoinColumns joinColumn : this.joinColumns) {
                joinColumns.add(joinColumn.withDefaultSchema(schema));
            }

            List<TableReference> tables = new ArrayList<>(this.tables.size());
            for (TableReference table : this.tables) {
                tables.add(table.withDefaultSchema(schema));
            }

            return new AnalyzedSql(id,
                                   query,
                                   tables,
                                   type,
                                   joinColumns,
                                   criteriaColumns,
                                   generatedSource,
                                   truncated,
                                   analysisFailed,
                                   analysisSkipped,
                                   isDml,
                                   runtimeMetrics,
                                   digest,
                                   normalizedDigest,
                                   schema,
                                   mismatchedJoins,
                                   lineNumber,
                                   analysisFailureReason,
                                   sourcedFiles,
                                   queryActivityStatus,
                                   modifiedForAnalysis,
                                   isMultiSchema);
        }

        public View view() {
            return view;
        }

        public Builder modifiedForAnalysis(boolean modifiedForAnalysis) {
            this.modifiedForAnalysis = modifiedForAnalysis;
            return this;
        }

        public class View {
            private View() {};

            public String getId() {
                return id;
            }

            public String getQuery() {
                return query;
            }

            public List<TableReference> getTables() {
                return tables;
            }

            public String getType() {
                return type;
            }

            public List<JoinColumns> getJoinColumns() {
                return joinColumns;
            }

            public List<ColumnReference> getCriteriaColumns() {
                return criteriaColumns;
            }

            public String getGeneratedSource() {
                return generatedSource;
            }

            public boolean isTruncated() {
                return truncated;
            }

            public boolean isAnalysisFailed() {
                return analysisFailed;
            }

            public RuntimeMetrics getRuntimeMetrics() {
                return runtimeMetrics;
            }

            public String getDigest() {
                return digest;
            }

            public String getSchema() {
                return schema;
            }

            public List<JoinColumns> getMismatchedJoins() {
                return mismatchedJoins;
            }

            public int getLineNumber() {
                return lineNumber;
            }

            public String getAnalysisFailureReason() {
                return analysisFailureReason;
            }

            public List<String> getSchemas() { return schemas; }

            public boolean isMultiSchema() {
                return isMultiSchema;
            }
        }
    }
}
