package com.github.safetytrick.sqlexplorer.commentary;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

/**
 * Tag definitions merged with their usage: a summary per tag name, whether it
 * comes from a stored definition (possibly unused) or only from assignments
 * (legacy tags with no definition yet).
 *
 * <p>DEFINITIONS ARE GLOBAL, USAGE IS NOT. A definition holds a tag's name and
 * colour, which mean the same thing in every environment — `system` should not
 * be blue in one corpus and orange in another. Only the associations are
 * environment-scoped, so the counts here describe the selected environment
 * while the names and colours describe the deployment (sqlx-rad).
 */
@Service
public class TagManagerService {

    private final TagRepository tagRepository;
    private final TagDefinitionRepository tagDefinitionRepository;
    private final CommentaryEnvironment environment;

    public TagManagerService(TagRepository tagRepository, TagDefinitionRepository tagDefinitionRepository,
                             CommentaryEnvironment environment) {
        this.tagRepository = tagRepository;
        this.tagDefinitionRepository = tagDefinitionRepository;
        this.environment = environment;
    }

    public List<TagSummary> getSummaries() {
        Map<String, Builder> byName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        tagDefinitionRepository.findAll().forEach(definition ->
            byName.computeIfAbsent(definition.getName(), Builder::new).color = definition.getColor());
        for (TagRepository.TagUsage usage : tagRepository.aggregateUsage(environment.name())) {
            Builder builder = byName.computeIfAbsent(usage.getName(), Builder::new);
            if ("query".equalsIgnoreCase(usage.getType())) {
                builder.queries += usage.getUses();
            } else if ("table".equalsIgnoreCase(usage.getType())) {
                builder.tables += usage.getUses();
            } else if ("schema".equalsIgnoreCase(usage.getType())) {
                builder.schemas += usage.getUses();
            } else {
                builder.other += usage.getUses();
            }
            if (usage.getLastUsed() != null
                && (builder.lastUsed == null || usage.getLastUsed().isAfter(builder.lastUsed))) {
                builder.lastUsed = usage.getLastUsed();
            }
        }
        return byName.values().stream()
            .map(Builder::build)
            .sorted(Comparator.comparing(TagSummary::lastUsed,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();
    }

    public TagDefinition define(String name, String color) {
        TagDefinition definition = tagDefinitionRepository.findById(name).orElseGet(() -> {
            TagDefinition created = new TagDefinition();
            created.setName(name);
            return created;
        });
        definition.setColor(color);
        return tagDefinitionRepository.save(definition);
    }

    /** Removes the definition and every assignment of the tag. */
    @Transactional
    public void delete(String name) {
        tagDefinitionRepository.deleteById(name);
        tagRepository.deleteAllByNameAndEnvironmentName(name, environment.name());
    }

    public record TagSummary(String name, String color, long queries, long tables, long schemas, long other, Instant lastUsed) { }

    private static final class Builder {
        private final String name;
        private String color;
        private long queries;
        private long tables;
        private long schemas;
        private long other;
        private Instant lastUsed;

        private Builder(String name) {
            this.name = name;
        }

        private TagSummary build() {
            return new TagSummary(name, color, queries, tables, schemas, other, lastUsed);
        }
    }
}
