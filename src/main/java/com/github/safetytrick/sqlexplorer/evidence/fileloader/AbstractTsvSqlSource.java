package com.github.safetytrick.sqlexplorer.evidence.fileloader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectReader;
import com.github.safetytrick.sqlexplorer.evidence.source.MeteredSql;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlInput;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSource;
import org.springframework.core.io.Resource;

public abstract class AbstractTsvSqlSource implements SqlSource {

    public abstract Resource getResource();
    @Override
    public Stream<SqlInput> getSql() {
        ObjectReader objectReader = CsvImporter.getPerformanceSchemaReader();
        try {
            MappingIterator<MeteredSql> iterator = objectReader.readValues(getResource().getInputStream());
            return Stream.generate(() -> null)
                .takeWhile(n -> iterator.hasNext())
                .map(n -> new SqlInput(iterator.getCurrentLocation().getLineNr(), iterator.next(), this));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
