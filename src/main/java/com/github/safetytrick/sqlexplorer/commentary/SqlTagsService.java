package com.github.safetytrick.sqlexplorer.commentary;

import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.evidence.SqlRepositoryService;
import com.github.safetytrick.sqlexplorer.model.TableRollup;
import extensions.com.github.safetytrick.sqlexplorer.avro.SqlProfile.SqlProfileExtension;
import org.springframework.stereotype.Service;

import static java.util.Comparator.comparing;

@Service
public class SqlTagsService {

    private final SqlRepositoryService sqlRepositoryService;
    private final TagsService tagsService;

    public SqlTagsService(SqlRepositoryService sqlRepositoryService, TagsService tagsService) {
        this.sqlRepositoryService = sqlRepositoryService;
        this.tagsService = tagsService;
    }

    public Stream<SqlProfile> getSqlByTag(String name) {
        return tagsService.findAllByNameAndType(name, "query").stream()
            .filter(Objects::nonNull)
            .map(this::buildTaggedAnalyzedSql)
            .sorted(SqlProfileExtension.comparator);
    }

    private SqlProfile buildTaggedAnalyzedSql(Tag tag) {
        String queryId = tag.getPathElements().get(tag.getType());
        return sqlRepositoryService.getById(queryId);
    }

    public Stream<TableRollup> getTablesByTag(String name) {
        return tagsService.findAllByNameAndType(name, "table")
            .stream()
            .filter(Objects::nonNull)
            .map(this::buildTaggedTable)
            .sorted(comparing(TableRollup::totalQueryTime)
                        .reversed());
    }

    private TableRollup buildTaggedTable(Tag tag) {
        Map<String, String> elements = tag.getPathElements();
        String schema = elements.get("schema");
        String table = elements.get("table");
        TableReference reference = TableReference.newTableReference(schema, table);
        return sqlRepositoryService.getTableRollup(reference, tagsService);
    }
}
