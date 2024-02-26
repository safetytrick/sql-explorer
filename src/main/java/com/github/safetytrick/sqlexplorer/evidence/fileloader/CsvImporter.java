package com.github.safetytrick.sqlexplorer.evidence.fileloader;

import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.safetytrick.sqlexplorer.evidence.source.MeteredSql;

class CsvImporter {
    public static ObjectReader getPerformanceSchemaReader() {
        return new CsvMapper()
            .registerModule(new JavaTimeModule())
            .readerFor(MeteredSql.class)
            .with(performanceSchemaExportSchema());
    }

    private static CsvSchema performanceSchemaExportSchema() {
        return CsvSchema.emptySchema().withNullValue("NULL").withColumnSeparator('\t').withHeader();
    }
}
