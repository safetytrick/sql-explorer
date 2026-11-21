package com.github.safetytrick.sqlexplorer.evidence;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.avro.JoinColumns;
import com.github.safetytrick.sqlexplorer.avro.QueryActivityStatus;
import com.github.safetytrick.sqlexplorer.avro.RuntimeMetrics;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.evidence.SqlEvidenceInputService.SqlRepositoryBuilder;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlInput;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSource;
import com.github.safetytrick.sqlexplorer.model.MergedSqlProfile;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MergeChildFieldParityTest {

    private static final String NORMALIZED_DIGEST = "shared-normalized-digest";
    private static final String QUERY = "select `f`.`name` from `folders` `f` where `f`.`uuid` = ?";

    private static void assertDescribesTheSameStatement(SqlProfile actual, SqlProfile expected) {
        assertThat(actual.getTables()).as("tables").isEqualTo(expected.getTables());
        assertThat(actual.getType()).as("type").isEqualTo(expected.getType());
        assertThat(actual.getJoinColumns()).as("joinColumns").isEqualTo(expected.getJoinColumns());
        assertThat(actual.getCriteriaColumns()).as("criteriaColumns").isEqualTo(expected.getCriteriaColumns());
        assertThat(actual.getSelectColumns()).as("selectColumns").isEqualTo(expected.getSelectColumns());
        assertThat(actual.getGroupByColumns()).as("groupByColumns").isEqualTo(expected.getGroupByColumns());
        assertThat(actual.getMismatchedJoins()).as("mismatchedJoins").isEqualTo(expected.getMismatchedJoins());
        assertThat(actual.getSchemas()).as("schemas").isEqualTo(expected.getSchemas());
        assertThat(actual.getSchemaName()).as("schemaName").isEqualTo(expected.getSchemaName());
        assertThat(actual.getIsMultiSchema()).as("isMultiSchema").isEqualTo(expected.getIsMultiSchema());
        assertThat(actual.getIsDml()).as("isDml").isEqualTo(expected.getIsDml());
        assertThat(actual.getTruncated()).as("truncated").isEqualTo(expected.getTruncated());
        assertThat(actual.getAnalysisFailed()).as("analysisFailed").isEqualTo(expected.getAnalysisFailed());
        assertThat(actual.getAnalysisFailureReason()).as("analysisFailureReason")
            .isEqualTo(expected.getAnalysisFailureReason());
        assertThat(actual.getGeneratedSource()).as("generatedSource").isEqualTo(expected.getGeneratedSource());
        assertThat(actual.getGeneratorCandidates()).as("generatorCandidates")
            .isEqualTo(expected.getGeneratorCandidates());
        assertThat(actual.getModifiedForAnalysis()).as("modifiedForAnalysis")
            .isEqualTo(expected.getModifiedForAnalysis());
    }

    @Test
    void aMergedChildCarriesTheRepresentativesAnalysis() {
        SqlRepositoryBuilder repository = new SqlRepositoryBuilder(new TestSource());

        SqlProfile representative = analyzed("digest-a", 100L).completeBuild("tdb");
        repository.addToIndex(representative);

        SqlProfile.Builder childBuilder = unanalyzed("digest-b", 5L);
        assertThat(repository.attemptMerge(NORMALIZED_DIGEST, childBuilder)).isTrue();
        SqlProfile child = childBuilder.completeBuild("tdb");

        assertThat(representative.getSelectColumns()).as("fixture sanity").isNotEmpty();
        assertDescribesTheSameStatement(child, representative);
    }

    @Test
    void theGroupAnswersTheSameWhicheverMemberIsFirst() {
        SqlRepositoryBuilder repository = new SqlRepositoryBuilder(new TestSource());

        SqlProfile representative = analyzed("digest-a", 100L).completeBuild("tdb");
        repository.addToIndex(representative);

        SqlProfile.Builder childBuilder = unanalyzed("digest-b", 5L);
        repository.attemptMerge(NORMALIZED_DIGEST, childBuilder);
        SqlProfile child = childBuilder.completeBuild("tdb");

        SqlProfile representativeFirst = new MergedSqlProfile(List.of(representative, child));
        SqlProfile childFirst = new MergedSqlProfile(List.of(child, representative));

        assertDescribesTheSameStatement(childFirst, representativeFirst);
    }

    private static SqlProfile.Builder analyzed(String digest, long executed) {
        return unanalyzed(digest, executed)
            .setTables(List.of(TableReference.fromString("tdb.folders")))
            .setJoinColumns(List.of(join("tdb.folders.uuid", "tdb.users.category_uuid")))
            .setCriteriaColumns(List.of(ColumnReference.fromComponents("tdb", "folders", "uuid")))
            .setSelectColumns(List.of(ColumnReference.fromComponents("tdb", "folders", "name")))
            .setGroupByColumns(List.of(ColumnReference.fromComponents("tdb", "folders", "name")))
            .setMismatchedJoins(List.of())
            .setGeneratedSource("hibernate")
            .setGeneratorCandidates(List.of(new com.github.safetytrick.sqlexplorer.avro.GeneratorCandidate(
                "hibernate", "criteria-root-alias")))
            .setModifiedForAnalysis(true);
    }

    private static SqlProfile.Builder unanalyzed(String digest, long executed) {
        return SqlProfile.newBuilder()
            .setId(digest)
            .setQuery(QUERY)
            .setType("SELECT")
            .setSchemaName("tdb")
            .setLineNumber(1)
            .setNormalizedDigest(NORMALIZED_DIGEST)
            .setQueryActivityStatus(QueryActivityStatus.ACTIVE)
            .setRuntimeMetrics(metrics(digest, executed));
    }

    private static JoinColumns join(String left, String right) {
        return JoinColumns.fromReferences(left, right);
    }

    private static RuntimeMetrics metrics(String digest, long executed) {
        return RuntimeMetrics.newBuilder()
            .setFullTableScan(false)
            .setExecuted(executed)
            .setErrors(0).setWarnings(0)
            .setTotalTime(BigDecimal.TEN)
            .setMaxTime(BigDecimal.ONE)
            .setAverageTime(BigDecimal.ONE)
            .setRowsSent(1).setAverageRowsSent(1)
            .setRowsScanned(1).setAverageRowsScanned(1)
            .setTempTables(0).setTempDiskTables(0)
            .setRowsSorted(0).setSortMergePasses(0)
            .setDigest(digest)
            .setLastSeen(LocalDateTime.now())
            .setFirstSeen(LocalDateTime.now().minusDays(1))
            .setLockTime(BigDecimal.ZERO)
            .setRowsAffected(0).setAverageRowsAffected(0)
            .completeBuild();
    }

    private static final class TestSource implements SqlSource {
        @Override
        public Stream<SqlInput> getSql() {
            return Stream.empty();
        }

        @Override
        public Instant getCreatedOn() {
            return Instant.now();
        }

        @Override
        public String getSourceRef() {
            return "merge-parity-test";
        }
    }
}
