package com.github.safetytrick.sqlexplorer.commentary;

import java.util.Map;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.evidence.SqlRepositoryService;
import com.github.safetytrick.sqlexplorer.model.SqlProfile;
import com.github.safetytrick.sqlexplorer.model.TableReference;
import com.github.safetytrick.sqlexplorer.model.TableRollup;
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


    private SqlProfile buildTaggedAnalyzedSql(Tag tag) {
        String queryId = tag.getPathElements().get(tag.getType());
        return sqlRepositoryService.getById(queryId);
    }
}
