package com.github.safetytrick.sqlexplorer.evidence.fileloader;

import tools.jackson.databind.ObjectReader;
import tools.jackson.dataformat.csv.CsvMapper;
import tools.jackson.dataformat.csv.CsvSchema;
import com.github.safetytrick.sqlexplorer.evidence.source.MeteredSql;

class CsvImporter {
    public static ObjectReader getPerformanceSchemaReader() {
        return new CsvMapper()
            .readerFor(MeteredSql.class)
            .with(performanceSchemaExportSchema());
    }

    private static CsvSchema performanceSchemaExportSchema() {
        return CsvSchema.emptySchema().withNullValue("NULL").withColumnSeparator('\t').withHeader();
    }
}
