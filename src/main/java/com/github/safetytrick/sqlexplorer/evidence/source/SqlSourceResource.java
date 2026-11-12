package com.github.safetytrick.sqlexplorer.evidence.source;

import com.github.safetytrick.sqlexplorer.environment.EnvironmentScoped;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.environment.EnvironmentContext;
import com.github.safetytrick.sqlexplorer.environment.EnvironmentRegistry;
import com.github.safetytrick.sqlexplorer.evidence.EnvironmentLoader;
import com.github.safetytrick.sqlexplorer.evidence.SqlImportStats;
import com.github.safetytrick.sqlexplorer.evidence.SqlRepositoryManager;
import com.github.safetytrick.sqlexplorer.evidence.fileloader.ImportedSqlSource;
import com.google.common.base.Strings;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder;
import org.springframework.web.util.UriComponents;

import static org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder.on;

@Tag(name="Source")
@EnvironmentScoped
@RestController
class SqlSourceResource {

    private final SqlRepositoryManager sqlRepositoryManager;
    private final EnvironmentLoader environmentLoader;
    private final EnvironmentContext environmentContext;
    private final EnvironmentRegistry environmentRegistry;
    private final SqlSourceEntityRepository sqlSourceEntityRepository;
    private final SourceRefEntityRepository sourceRefEntityRepository;
    private final SqlSourceEntityService sqlSourceEntityService;

    SqlSourceResource(SqlRepositoryManager sqlRepositoryManager, EnvironmentLoader environmentLoader,
                      EnvironmentContext environmentContext,
                      EnvironmentRegistry environmentRegistry,
                      SqlSourceEntityRepository sqlSourceEntityRepository,
                      SourceRefEntityRepository sourceRefEntityRepository,
                      SqlSourceEntityService sqlSourceEntityService) {
        this.sqlRepositoryManager = sqlRepositoryManager;
        this.environmentLoader = environmentLoader;
        this.environmentContext = environmentContext;
        this.environmentRegistry = environmentRegistry;
        this.sqlSourceEntityRepository = sqlSourceEntityRepository;
        this.sourceRefEntityRepository = sourceRefEntityRepository;
        this.sqlSourceEntityService = sqlSourceEntityService;
    }

    @GetMapping("/sources/{id}")
    public SqlSourceEntity getSqlSource(@PathVariable Integer id) {
        return sqlSourceEntityRepository.findById(id).orElseThrow();
    }

    @GetMapping("/sources/{id}/body")
    public ResponseEntity<Resource> getSqlSourceBody(@PathVariable Integer id) throws SQLException {

        SqlSourceEntity entity = sqlSourceEntityRepository.findById(id).orElseThrow();
        Resource resource = new InputStreamResource(entity.getContent().getBinaryStream());

        String filename = fileNameOfSourceUrl(entity.getSourceUrl());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(resource);
    }

    private String fileNameOfSourceUrl(String sourceUrl) {
        return sourceUrl.substring(sourceUrl.lastIndexOf("/") + 1);
    }

    @GetMapping("/sources/active")
    public Stream<ImportedSqlSource> listImportedSqlSources() {
        return sqlRepositoryManager.getRepositories(environmentContext.current().getName())
            .stream()
            .map(repo -> new ImportedSqlSource(repo.getSource(), "unknown", repo.getImportStats()))
            .sorted(Comparator.comparing(source -> source.getImportStats().count()));
    }

    /** All persisted sources, each labeled with the environment it is attributed to. */
    @GetMapping("/sources")
    public Stream<PersistedSource> listPersistentImportedSqlSources() {
        Map<String, String> attribution = attributionByRef();
        return sqlSourceEntityRepository.findDistinctBy().stream()
                .map(ref -> sqlSourceEntityRepository.findLatestBySourceRef(ref.getSourceRef()))
                .flatMap(Optional::stream)
                .map(entity -> new PersistedSource(entity, attribution.get(entity.getSourceRef())))
                .sorted(Comparator.comparing(persisted -> persisted.source().getSourceRef()));
    }

    @PostMapping("/sources")
    public ResponseEntity<Void> handleSqlSourceUpload(SqlSourceUpload body) {
        String sourceUrl = Stream.of(body.sourceUrl(), body.file.getOriginalFilename())
                .filter(str -> !Strings.isNullOrEmpty(str))
                .findFirst().orElse(body.sourceRef());

        // Required rather than optional. An export whose capture time is
        // unknown can never have one attached later — nobody but the uploader
        // knows it — and a corpus that cannot say when it was taken cannot be
        // reasoned about at all (sqlx-sy5).
        Instant capturedAt = parseCapturedAt(body.capturedAt());

        SqlSourceEntity entity = sqlSourceEntityService.saveUploadedSqlSource(body.sourceRef(),
                sourceUrl,
                body.file().getResource(),
                environmentContext.current().getName(),
                new SqlSourceEntityService.CaptureDetails(capturedAt, body.serverVersion(),
                    body.serverDigestsSize(), body.serverMaxDigestLength()));

        UriComponents uriComponents = MvcUriComponentsBuilder.fromMethodCall(on(SqlSourceResource.class).getSqlSource(entity.getId())).buildAndExpand(1);

        return ResponseEntity.created(uriComponents.toUri()).build();
    }

    /**
     * The capture instant, or a 400 saying what is missing and why.
     *
     * <p>Deliberately not defaulted to "now". Upload time is already recorded
     * as {@code createdOn}, and the two are routinely months apart — silently
     * conflating them would put a confident wrong timestamp on the one field
     * the confidence signals hang off.
     */
    private static Instant parseCapturedAt(String capturedAt) {
        // 400, not the 500 an IllegalArgumentException produces here. The
        // uploader supplied a bad request and the message tells them how to fix
        // it; reporting that as a server fault sends them looking at logs they
        // have no access to and cannot act on.
        if (Strings.isNullOrEmpty(capturedAt)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "capturedAt is required: when the export was taken off the source server, ISO-8601. "
                    + "It cannot be recovered later, and upload time is not a substitute.");
        }
        try {
            return Instant.parse(capturedAt);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "capturedAt must be ISO-8601, for example 2026-08-29T14:03:35Z; got: " + capturedAt, e);
        }
    }

    @PostMapping("/sources/load")
    public SqlImportStats reloadSources(@RequestBody(required = false) List<SourceRefRequest> sourceParam) {
        String environmentName = environmentContext.current().getName();
        return environmentLoader.load(environmentName, resolveSources(environmentName, sourceParam));
    }

    /** Load configured + persisted sources into a specific environment (independent of the request's selection). */
    @PostMapping("/environments/{name}/load")
    public SqlImportStats loadEnvironment(@PathVariable String name,
                                          @RequestBody(required = false) List<SourceRefRequest> sourceParam) {
        if (environmentRegistry.get(name) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown environment: " + name);
        }
        return environmentLoader.load(name, resolveSources(name, sourceParam));
    }

    /**
     * Resolve the latest persisted source for each requested ref. When no refs are
     * given, all refs attributed to the environment are used; explicitly requested
     * refs are honored regardless of attribution. The environment's configured
     * sources (x-statement-analysis, performance-schema files) are added by
     * {@link EnvironmentLoader#load}.
     */
    private Stream<SqlSource> resolveSources(String environmentName, List<SourceRefRequest> sourceParam) {
        List<? extends SourceRefProjection> sources;
        if (sourceParam == null || sourceParam.isEmpty()) {
            sources = sourceRefsForEnvironment(environmentName);
        } else {
            sources = sourceParam;
        }
        return sources.stream().map(ref ->
                sqlSourceEntityRepository.findLatestBySourceRef(ref.getSourceRef())
        ).flatMap(Optional::stream);
    }

    /** Persisted source refs attributed to the environment. */
    private List<SourceRefProjection> sourceRefsForEnvironment(String environmentName) {
        Map<String, String> attribution = attributionByRef();
        return sqlSourceEntityRepository.findDistinctBy().stream()
                .filter(ref -> environmentName.equals(attribution.get(ref.getSourceRef())))
                .collect(Collectors.toList());
    }

    /**
     * Environment attribution for every persisted source ref. Unattributed
     * (legacy) refs belong to the default environment; encountering one
     * persists that attribution.
     */
    private Map<String, String> attributionByRef() {
        String defaultEnvironment = environmentRegistry.getDefaultName();
        Map<String, String> attribution = new HashMap<>();
        sourceRefEntityRepository.findAll()
                .forEach(ref -> attribution.put(ref.getName(), ref.getEnvironmentName()));
        for (SourceRefProjection ref : sqlSourceEntityRepository.findDistinctBy()) {
            if (attribution.get(ref.getSourceRef()) == null) {
                sqlSourceEntityService.attributeSourceRef(ref.getSourceRef(), defaultEnvironment);
                attribution.put(ref.getSourceRef(), defaultEnvironment);
            }
        }
        return attribution;
    }

    record PersistedSource(SqlSourceEntity source, String environmentName) { }


    @SuppressWarnings("ClassCanBeRecord") // todo, test if this works with SourceRefProjection and SqlSourceEntityRepository
    public static class SourceRefRequest implements SourceRefProjection {
        private final String sourceRef;

        public SourceRefRequest(String sourceRef) {
            this.sourceRef = sourceRef;
        }

        @Override
        public String getSourceRef() {
            return sourceRef;
        }
    }

    /**
     * An uploaded source, and the capture context it must arrive with.
     *
     * <p>A TSV export of {@code sys.x$statement_analysis} carries nothing about
     * the server it came from or when it was taken, and those facts cannot be
     * recovered afterwards — reading them off a live connection later would
     * attribute one server's settings to another's data. So they are supplied
     * here, by whoever ran the export and is the only party that knows
     * (sqlx-sy5).
     *
     * @param capturedAt              when the export was taken, ISO-8601. Required.
     * @param serverVersion           the source server's version. Optional.
     * @param serverDigestsSize       its `performance_schema_digests_size` at
     *                                capture. Optional, and the signal for
     *                                whether digests were being evicted.
     * @param serverMaxDigestLength   its `performance_schema_max_digest_length`
     *                                at capture. Optional, and what says
     *                                whether truncation was expected.
     */
    record SqlSourceUpload(String sourceRef, String sourceUrl, MultipartFile file,
                           String capturedAt,
                           String serverVersion,
                           Integer serverDigestsSize,
                           Integer serverMaxDigestLength) { }

}
