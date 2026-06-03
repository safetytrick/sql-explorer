package com.github.safetytrick.sqlexplorer.evidence.source;

import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.evidence.SqlImportStats;
import com.github.safetytrick.sqlexplorer.evidence.SqlRepositoryManager;
import com.github.safetytrick.sqlexplorer.evidence.fileloader.ImportedSqlSource;
import com.google.common.base.Strings;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder;
import org.springframework.web.util.UriComponents;

import static org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder.on;

@RestController
class SqlSourceResource {

    private final SqlRepositoryManager sqlRepositoryManager;
    private final SqlSourceEntityRepository sqlSourceEntityRepository;
    private final SqlSourceEntityService sqlSourceEntityService;

    SqlSourceResource(SqlRepositoryManager sqlRepositoryManager, SqlSourceEntityRepository sqlSourceEntityRepository, SqlSourceEntityService sqlSourceEntityService) {
        this.sqlRepositoryManager = sqlRepositoryManager;
        this.sqlSourceEntityRepository = sqlSourceEntityRepository;
        this.sqlSourceEntityService = sqlSourceEntityService;
    }

    @GetMapping("/api/sql-explorer/sources/{id}")
    public SqlSourceEntity getSqlSource(@PathVariable Integer id) {
        return sqlSourceEntityRepository.findById(id).orElseThrow();
    }

    @GetMapping("/api/sql-explorer/sources/{id}/body")
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

    @GetMapping("/api/sql-explorer/sources/active")
    public Stream<ImportedSqlSource> listImportedSqlSources() {
        return sqlRepositoryManager.getRepositories()
            .stream()
            .map(repo -> new ImportedSqlSource(repo.getSource(), "unknown", repo.getImportStats()))
            .sorted(Comparator.comparing(source -> source.getImportStats().count()));
    }

    @GetMapping("/api/sql-explorer/sources")
    public Stream<SqlSourceEntity> listPersistentImportedSqlSources() {
        List<SourceRefProjection> pairs = sqlSourceEntityRepository.findDistinctBy();
        return pairs.stream().map(ref ->
                    sqlSourceEntityRepository.findLatestBySourceRef(ref.getSourceRef())
                )
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(SqlSourceEntity::getSourceRef));
    }

    @PostMapping("/api/sql-explorer/sources")
    public ResponseEntity<Void> handleSqlSourceUpload(SqlSourceUpload body) {
        String sourceUrl = Stream.of(body.sourceUrl(), body.file.getOriginalFilename())
                .filter(str -> !Strings.isNullOrEmpty(str))
                .findFirst().orElse(body.sourceRef());

        SqlSourceEntity entity = sqlSourceEntityService.saveUploadedSqlSource(body.sourceRef(),
                sourceUrl,
                body.file().getResource());

        UriComponents uriComponents = MvcUriComponentsBuilder.fromMethodCall(on(SqlSourceResource.class).getSqlSource(entity.getId())).buildAndExpand(1);

        return ResponseEntity.created(uriComponents.toUri()).build();
    }

    @PostMapping("/api/sql-explorer/sources/load")
    public SqlImportStats reloadSources(@RequestBody(required = false) List<SourceRefRequest> sourceParam) {
        // todo, this doesn't work with XStatementAnalysisLoader (XStatementAnalysisLoader does not use SqlSourceEntity)
        List<? extends SourceRefProjection> sources;
        if (sourceParam == null || sourceParam.isEmpty()) {
            sources = sqlSourceEntityRepository.findDistinctBy();
        } else {
            sources = sourceParam;
        }
        Stream<SqlSource> entityStream = sources.stream().map(ref ->
                sqlSourceEntityRepository.findLatestBySourceRef(ref.getSourceRef())
        ).flatMap(Optional::stream);

        return sqlRepositoryManager.loadFromSqlSources(entityStream);
    }


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

    record SqlSourceUpload(String sourceRef, String sourceUrl, MultipartFile file) { }

}
