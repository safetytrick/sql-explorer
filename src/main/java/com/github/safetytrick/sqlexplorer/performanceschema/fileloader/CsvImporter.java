package com.github.safetytrick.sqlexplorer.performanceschema.fileloader;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.StringTokenizer;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.safetytrick.sqlexplorer.analysis.SqlExplorer;
import com.github.safetytrick.sqlexplorer.core.AnalyzedSql;
import com.github.safetytrick.sqlexplorer.source.MeteredSql;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.ParseException;

public class CsvImporter {
    private static final double ERROR_THRESHOLD = 0.5;

    private static CsvSchema performanceSchemaExportSchema() {
        return CsvSchema.emptySchema().withNullValue("NULL").withColumnSeparator('\t').withHeader();
    }

    public static ObjectReader getPerformanceSchemaReader() {
        return new CsvMapper()
            .registerModule(new JavaTimeModule())
            .readerFor(MeteredSql.class)
            .with(performanceSchemaExportSchema());
    }

    public static void main(String[] args) throws IOException {
        try (PrintWriter writer = new PrintWriter("out2.txt", StandardCharsets.UTF_8)) {
            String filename = "src/main/docker-local/config/statement-analysis/dc1-pxc-04-20221021.tsv";

            ObjectReader objectReader = getPerformanceSchemaReader();

            MappingIterator<MeteredSql> iterator = objectReader.readValues(new File(filename));

            SqlExplorer sqlExplorer = new SqlExplorer();

            while (iterator.hasNext()) {
                JsonLocation currentLocation = iterator.getCurrentLocation();
                MeteredSql row = iterator.next();
                if (row.getQuery() == null) {
                    // skip this strange row
                    continue;
                }
                String firstToken = firstToken(row.getQuery());
                int lineNumber = currentLocation.getLineNr();
                try {
                    writer.println("--- (" + lineNumber + " " + firstToken +") ----------------------------------------");
                    double errorPercentage = row.getErrors() / ((double)row.getExecuted());
                    if (errorPercentage > ERROR_THRESHOLD) {
                        writer.println("Ignored sql, error percentage above 50%: " + (errorPercentage * 100));
                        continue;
                    }
                    AnalyzedSql.Builder builder = AnalyzedSql.builder();
                    sqlExplorer.analyzeSql(row.getQuery(), builder);
                    AnalyzedSql analyzedSql = builder.build();

                    if (analyzedSql.getTables().isEmpty()) {
                        writer.println(" (no tables)");
                    } else {
                        writer.print(" ");
                        writer.println(analyzedSql.getTables());
                        writeFinding("criteriaColumns", writer, analyzedSql.getCriteriaColumns());
                        writeFinding("joinColumns", writer, analyzedSql.getJoinColumns());
                    }

                } catch (JSQLParserException e) {
                    String message = e.getMessage();

                    int columnNumber = currentLocation.getColumnNr();

                    if (e.getCause() instanceof ParseException parseException) {
                        columnNumber = parseException.currentToken.next.beginColumn;

                        if (parseException.getMessage().contains("Encountered unexpected token:<EOF>")) {
                            message = "sql truncated";
                        } else {
                            message = "unsupported sql - " + firstToken;
                        }
                    }


                    writer.println("  [WARN] Unable to process row @[line: " + lineNumber + ", column:" + columnNumber + "] - " + message);
                } catch (Exception e) {
                    writer.println("  [WARN] Unable to process row @" + iterator.getCurrentLocation() + " - " + e.getMessage());
                }
            }
        }
    }

    private static void writeFinding(String property, PrintWriter writer, List<?> result) {
        if (result == null || result.isEmpty()) {
            return;
        }
        writer.println("   -" + property + ": " + result);
    }

    private static String firstToken(String sql) {
        StringTokenizer tokenizer = new StringTokenizer(sql);
        if (tokenizer.hasMoreTokens()) {
            return tokenizer.nextToken();
        }
        return null;
    }

}
