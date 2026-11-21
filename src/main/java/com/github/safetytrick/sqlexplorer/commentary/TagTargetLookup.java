package com.github.safetytrick.sqlexplorer.commentary;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

@Service
public class TagTargetLookup {

    private final TagRepository tagRepository;
    private final CommentaryEnvironment environment;

    TagTargetLookup(TagRepository tagRepository, CommentaryEnvironment environment) {
        this.tagRepository = tagRepository;
        this.environment = environment;
    }

    public List<List<Tag>> tagsFor(List<TagTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            return List.of();
        }
        List<TagTarget> asked = targets.stream().map(TagTarget::normalized).toList();
        String environmentName = environment.name();

        Map<TagTarget, List<Tag>> found = new HashMap<>();
        collect(found, asked, TagTarget.SCHEMA, environmentName);
        collect(found, asked, TagTarget.TABLE, environmentName);
        collect(found, asked, TagTarget.COLUMN, environmentName);
        collect(found, asked, TagTarget.QUERY, environmentName);

        List<List<Tag>> answer = new ArrayList<>(asked.size());
        for (TagTarget target : asked) {
            answer.add(found.getOrDefault(target, List.of()));
        }
        return List.copyOf(answer);
    }

    private void collect(Map<TagTarget, List<Tag>> into, List<TagTarget> asked,
                         String type, String environmentName) {
        List<TagTarget> ofType = asked.stream().filter(target -> type.equals(target.type())).toList();
        if (ofType.isEmpty()) {
            return;
        }
        List<Tag> rows = TagTarget.QUERY.equals(type)
            ? tagRepository.findAllByTypeAndQueryDigestInAndEnvironmentName(
                type, ofType.stream().map(TagTarget::digest).distinct().toList(), environmentName)
            : tagRepository.findAllByTypeAndSchemaNameInAndEnvironmentName(
                type, ofType.stream().map(TagTarget::schema).distinct().toList(), environmentName);

        for (Tag tag : rows) {
            into.computeIfAbsent(tag.getTarget(), key -> new ArrayList<>()).add(tag);
        }
    }
}
