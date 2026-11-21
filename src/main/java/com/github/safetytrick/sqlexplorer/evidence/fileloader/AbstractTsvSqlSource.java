package com.github.safetytrick.sqlexplorer.evidence.fileloader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.stream.Stream;

import tools.jackson.databind.MappingIterator;
import tools.jackson.databind.ObjectReader;
import com.github.safetytrick.sqlexplorer.evidence.source.MeteredSql;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlInput;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSource;
import org.springframework.core.io.Resource;

public abstract class AbstractTsvSqlSource implements SqlSource {

    public abstract Resource getResource();

    @Override
    public com.github.safetytrick.sqlexplorer.evidence.sink.CaptureContext.Origin getCaptureOrigin() {
        return com.github.safetytrick.sqlexplorer.evidence.sink.CaptureContext.Origin.CSV_EXPORT;
    }

    @Override
    public Stream<SqlInput> getSql() {
        ObjectReader objectReader = CsvImporter.getPerformanceSchemaReader();
        try {
            MappingIterator<MeteredSql> iterator = objectReader.readValues(getResource().getInputStream());
            return Stream.generate(() -> null)
                .takeWhile(n -> iterator.hasNext())
                .map(n -> new SqlInput(iterator.currentLocation().getLineNr(), iterator.next(), this));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
