package com.github.safetytrick.sqlexplorer.profiles;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.github.safetytrick.sqlexplorer.evidence.SourceScope;

public record QuerySearchRequest(
    String queryRegex,
    Range avgTimeMs,
    Range maxTimeMs,
    Range avgRowsScanned,
    Range selectivity,
    Range rowsSorted,
    Range executed,
    Activity activity,
    List<String> types,
    DateRange firstSeen,
    DateRange lastSeen,
    List<String> tags,
    TagMode tagMode,
    Boolean untaggedOnly,
    List<String> tableTags,
    List<String> excludeTableTags,
    List<String> excludeTypes,
    List<String> excludeTags,
    List<String> excludeQueries,
    List<String> sources,
    List<String> excludeSources,
    List<String> schemas,
    List<String> excludeSchemas,
    List<String> tables,
    TableMode tableMode,
    List<String> excludeTables,
    List<String> columns,
    List<String> indexes,
    HintAction hinted,
    String generator,
    Flag fullScan,
    Flag tmpTable,
    Flag tmpDisk,
    Flag errors,
    Flag warnings,
    Flag truncated,
    Flag multiSchema,
    Range tableCount,
    Flag selfJoin,
    Boolean analyzerWarnings,
    Boolean hasExamples,
    Boolean analysisFailed,
    Boolean cleanAnalysis,
    Boolean analysisSkipped,
    FailureKind failureKind,
    Sort sort,
    Integer limit,
    Integer offset
) {
    public record Range(Double min, Double max) { }

    public record DateRange(
        @JsonDeserialize(using = StartDateTimeDeserializer.class) LocalDateTime after,
        @JsonDeserialize(using = EndDateTimeDeserializer.class) LocalDateTime before) { }

    abstract static class FlexibleDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {
        private final LocalTime dateOnlyTime;

        FlexibleDateTimeDeserializer(LocalTime dateOnlyTime) {
            this.dateOnlyTime = dateOnlyTime;
        }

        @Override
        public LocalDateTime deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            String text = parser.getValueAsString();
            if (text == null || text.isBlank()) {
                return null;
            }
            text = text.trim().replace(' ', 'T');
            try {
                return LocalDateTime.parse(text);
            } catch (DateTimeParseException notDateTime) {
                return LocalDate.parse(text).atTime(dateOnlyTime);
            }
        }
    }

    static final class StartDateTimeDeserializer extends FlexibleDateTimeDeserializer {
        StartDateTimeDeserializer() { super(LocalTime.MIN); }
    }

    static final class EndDateTimeDeserializer extends FlexibleDateTimeDeserializer {
        EndDateTimeDeserializer() { super(LocalTime.MAX); }
    }

    public enum Activity { ANY, ACTIVE, DORMANT }

    public enum TagMode { ANY, ALL }

    public enum TableMode { ANY, ALL }

    public enum Flag { ANY, YES, NO }

    public enum FailureKind { ANALYZER, PARSE }

    public enum HintAction { ANY, USE, FORCE, IGNORE }

    public enum Sort { IMPACT, AVG_TIME, MAX_TIME, AVG_ROWS_SCANNED, SELECTIVITY, SELECTIVITY_ASC, EXECUTIONS, LAST_SEEN }

    public static final QuerySearchRequest UNCONSTRAINED = new QuerySearchRequest(
        null, null, null, null, null, null, null, null, null, null, null, null, null, null,
        null, null, null, null, null, null, null, null, null, null, null, null, null, null,
        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

    public SourceScope sourceScope() {
        return SourceScope.of(sources, excludeSources);
    }
}
