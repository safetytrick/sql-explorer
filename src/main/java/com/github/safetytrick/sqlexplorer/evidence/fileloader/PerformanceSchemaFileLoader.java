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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.github.safetytrick.sqlexplorer.evidence.SqlRepositoryManager;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSource;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSourceEntity;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSourceEntityService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;

@ConditionalOnProperty(value = "performance-schema-file-loader.enabled", havingValue = "true")
@Component
class PerformanceSchemaFileLoader {

    private static final Logger log = LoggerFactory.getLogger(PerformanceSchemaFileLoader.class);


    private final SqlRepositoryManager sqlRepositoryManager;
    private final PerformanceSchemaFileLoaderConfig config;
    private final SqlSourceEntityService sqlSourceEntityService;
    private final DateTimeFormatter dateTimeFormatter;
    private final Pattern filePattern;

    public PerformanceSchemaFileLoader(SqlRepositoryManager sqlRepositoryManager,
                                       PerformanceSchemaFileLoaderConfig config, SqlSourceEntityService sqlSourceEntityService) {
        this.sqlRepositoryManager = sqlRepositoryManager;
        this.config = config;
        this.filePattern = Pattern.compile(config.getPattern(), Pattern.CASE_INSENSITIVE);

        this.dateTimeFormatter = DateTimeFormatter.ofPattern(config.getDateFormat());
        this.sqlSourceEntityService = sqlSourceEntityService;
    }


    @PostConstruct
    public void initialize() {
        log.info("Initialized file loader with: {}", config.getDirectory());

        List<? extends AbstractTsvSqlSource> sqlSources = getSqlSources();
        for (AbstractTsvSqlSource sqlSource : sqlSources) {
            SqlSourceEntity sqlSourceEntity = sqlSourceEntityService.saveSqlSource(sqlSource);
            log.info("Found file: {} first loaded on: {}", sqlSourceEntity.getSourceUrl(), sqlSourceEntity.getCreatedOn());
        }

        sqlRepositoryManager.loadFromSqlSources(sqlSources.parallelStream());
    }


    private List<? extends AbstractTsvSqlSource> getSqlSources() throws PerformanceSchemaFileLoaderConfigException {
        if (!config.isEnabled()) {
            return Collections.emptyList();
        }

        File directory = new File(config.getDirectory());

        File[] matchingFiles = directory.listFiles(file -> file.isFile() && filePattern.matcher(file.getName()).matches());
        if (matchingFiles == null) {
            throw new PerformanceSchemaFileLoaderConfigException("Invalid directory: " + config.getDirectory());
        }

        return Arrays.stream(matchingFiles).map(this::mapFileToSource)
            .collect(Collectors.groupingBy(SqlSource::getSourceRef))
            .values()
            .stream().map(list -> list.stream().max(Comparator.comparing(SqlSource::getCreatedOn)))
            .flatMap(Optional::stream)
            .collect(Collectors.toList());

    }

    private FileSqlSource mapFileToSource(File file) throws PerformanceSchemaFileLoaderConfigException {
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


    public static class PerformanceSchemaFileLoaderConfigException extends RuntimeException {
        public PerformanceSchemaFileLoaderConfigException(String message) {
            super(message);
        }
    }

}
