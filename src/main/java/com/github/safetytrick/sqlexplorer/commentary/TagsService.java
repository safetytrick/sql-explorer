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
    public List<Tag> findAllByTarget(TagTarget target) {
        TagTarget wanted = target.normalized();
        return this.tagRepository.findAllByTypeAndSchemaNameAndEnvironmentName(
                wanted.type(), wanted.schema(), environment.name()).stream()
            .filter(tag -> tag.getTarget().equals(wanted))
            .toList();
    }

    public TagLoader getBulkTagLoaderByType(String type) {
        List<Tag> allByType = tagRepository.findAllByTypeAndEnvironmentName(type, environment.name());
        // Grouped by TARGET: a path is null for a name containing '/', and
        // groupingBy refuses a null key — so this threw on the first such row
        // and took every rollup in its schema with it (sqlx-3ai).
        Map<TagTarget,List<Tag>> tagsByTarget = allByType.stream()
            .collect(Collectors.groupingBy(Tag::getTarget));

        return target -> tagsByTarget.get(target.normalized());
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
        Map<TagTarget,List<Tag>> tagsByTarget = allByType.stream()
            .collect(Collectors.groupingBy(Tag::getTarget));

        return target -> tagsByTarget.get(target.normalized());
    }
}
