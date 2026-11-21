package com.github.safetytrick.sqlexplorer.evidence.fileloader;

import java.io.File;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.environment.DatabaseServer;
import com.github.safetytrick.sqlexplorer.environment.SourceDefinition;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSource;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSourceEntity;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSourceEntityService;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSourceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;

@Component
class PerformanceSchemaFileSourceFactory implements SqlSourceFactory {

    static final String TYPE = "performance-schema-files";

    private static final Logger log = LoggerFactory.getLogger(PerformanceSchemaFileSourceFactory.class);

    private final SqlSourceEntityService sqlSourceEntityService;

    PerformanceSchemaFileSourceFactory(SqlSourceEntityService sqlSourceEntityService) {
        this.sqlSourceEntityService = sqlSourceEntityService;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Stream<? extends SqlSource> create(String environmentName, DatabaseServer server, SourceDefinition definition) {
        if (definition.getDirectory() == null || definition.getPattern() == null || definition.getDateFormat() == null) {
            throw new PerformanceSchemaFileLoaderConfigException(
                "performance-schema-files source requires directory, pattern and dateFormat");
        }
        Pattern filePattern = Pattern.compile(definition.getPattern(), Pattern.CASE_INSENSITIVE);
        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern(definition.getDateFormat());

        log.info("Scanning for performance-schema files in: {}", definition.getDirectory());
        List<FileSqlSource> sqlSources = scan(definition.getDirectory(), filePattern, dateTimeFormatter);
        for (FileSqlSource sqlSource : sqlSources) {
            SqlSourceEntity entity = sqlSourceEntityService.saveSqlSource(sqlSource, environmentName);
            log.info("Found file: {} first loaded on: {}", entity.getSourceUrl(), entity.getCreatedOn());
        }
        return sqlSources.stream();
    }

    private List<FileSqlSource> scan(String directoryPath, Pattern filePattern, DateTimeFormatter dateTimeFormatter) {
        File directory = new File(directoryPath);
        File[] matchingFiles = directory.listFiles(file -> file.isFile() && filePattern.matcher(file.getName()).matches());
        if (matchingFiles == null) {
            throw new PerformanceSchemaFileLoaderConfigException("Invalid directory: " + directoryPath);
        }

        return Arrays.stream(matchingFiles)
            .map(file -> mapFileToSource(file, filePattern, dateTimeFormatter))
            .collect(Collectors.groupingBy(SqlSource::getSourceRef))
            .values()
            .stream().map(list -> list.stream().max(Comparator.comparing(SqlSource::getCreatedOn)))
            .flatMap(Optional::stream)
            .collect(Collectors.toList());
    }

    private FileSqlSource mapFileToSource(File file, Pattern filePattern, DateTimeFormatter dateTimeFormatter) {
        Matcher matcher = filePattern.matcher(file.getName());

        if (!matcher.matches()) {
            throw new IllegalArgumentException("All input files should match the file regex.");
        }

        String id = matcher.group("id");
        Instant createdOn = null;
        try {
            String date = matcher.group("date");
            TemporalAccessor dateAccessor = dateTimeFormatter.parse(date);
            if (dateAccessor.isSupported(ChronoField.INSTANT_SECONDS)) {
                createdOn = Instant.from(dateAccessor);
            } else {
                LocalDate localDate = LocalDate.from(dateAccessor);
                ZonedDateTime zonedTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(file.lastModified()), ZoneId.systemDefault());
                createdOn = ((ZonedDateTime) localDate.adjustInto(zonedTime)).toInstant();
            }
        } catch (IllegalArgumentException ignore) { }

        if (createdOn == null) {
            createdOn = Instant.ofEpochMilli(file.lastModified());
        }

        return new FileSqlSource(id, new FileSystemResource(file), createdOn);
    }

    static class PerformanceSchemaFileLoaderConfigException extends RuntimeException {
        PerformanceSchemaFileLoaderConfigException(String message) {
            super(message);
        }
    }
}
