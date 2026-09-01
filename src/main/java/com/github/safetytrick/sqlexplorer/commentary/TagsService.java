package com.github.safetytrick.sqlexplorer.commentary;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

@Service
public class TagsService implements TagLoader {

    private final TagRepository tagRepository;
    private final CommentaryEnvironment environment;

    /**
     * The scope is resolved here rather than by every caller.
     *
     * <p>This service is the whole read surface for tags outside this package —
     * profile building, query search, the graph — and none of those are about
     * environments. Threading the name through them would put the chance to
     * forget it in a dozen places, where forgetting means silently reading
     * another corpus's tags rather than failing (sqlx-rad).
     */
    TagsService(TagRepository tagRepository, CommentaryEnvironment environment) {
        this.tagRepository = tagRepository;
        this.environment = environment;
    }

    public List<Tag> findAllByNameAndType(String name, String type) {
        return this.tagRepository.findAllByNameAndTypeAndEnvironmentName(name, type, environment.name());
    }

    public List<Tag> findAllByType(String type) {
        return this.tagRepository.findAllByTypeAndEnvironmentName(type, environment.name());
    }

    @Override
    public List<Tag> findAllByPath(String path) {
        return this.tagRepository.findAllByPathAndEnvironmentName(path, environment.name());
    }

    public TagLoader getBulkTagLoaderByType(String type) {
        List<Tag> allByType = tagRepository.findAllByTypeAndEnvironmentName(type, environment.name());
        Map<String,List<Tag>> tagsByPath = allByType.stream()
            .collect(Collectors.groupingBy(Tag::getPath));

        return tagsByPath::get;
    }

    /**
     * Every table tag in one schema, keyed by address.
     *
     * <p>Was a path-prefix LIKE. Now a predicate on the column that actually
     * holds the schema, which is both faster and says what it means (sqlx-11v).
     */
    public TagLoader getTableTagLoaderForSchema(String schema) {
        List<Tag> allByType = tagRepository
            .findAllByTypeAndSchemaNameAndEnvironmentName("table", schema, environment.name());
        Map<String,List<Tag>> tagsByPath = allByType.stream()
            .collect(Collectors.groupingBy(Tag::getPath));

        return tagsByPath::get;
    }
}
