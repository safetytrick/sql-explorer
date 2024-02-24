package com.github.safetytrick.sqlexplorer.anno;

import java.util.Map;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.core.AnalyzedSql;
import com.github.safetytrick.sqlexplorer.core.TableReference;
import com.github.safetytrick.sqlexplorer.core.TableRollup;
import com.github.safetytrick.sqlexplorer.service.SqlRepositoryService;
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

    public Stream<AnalyzedSql> getSqlByTag(String name) {
        return tagsService.findAllByNameAndType(name, "query").stream()
            .map(this::buildTaggedAnalyzedSql);
    }

    public Stream<TableRollup> getTablesByTag(String name) {
        return tagsService.findAllByNameAndType(name, "table")
            .stream().map(this::buildTaggedTable)
            .sorted(comparing(TableRollup::totalQueryTime)
                        .reversed());
    }

    private TableRollup buildTaggedTable(Tag tag) {
        Map<String, String> elements = tag.getPathElements();
        String schema = elements.get("schema");
        String table = elements.get("table");
        TableReference reference = new TableReference(schema, table);
        return sqlRepositoryService.getTableRollup(reference, tagsService);
    }


    private AnalyzedSql buildTaggedAnalyzedSql(Tag tag) {
        String queryId = tag.getPathElements().get(tag.getType());
        return sqlRepositoryService.getById(queryId);
    }
}
