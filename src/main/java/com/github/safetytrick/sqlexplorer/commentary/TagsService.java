package com.github.safetytrick.sqlexplorer.commentary;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

@Service
public class TagsService implements TagLoader {

    private final TagRepository tagRepository;
    private final CommentaryEnvironment environment;

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
        Map<TagTarget,List<Tag>> tagsByTarget = allByType.stream()
            .collect(Collectors.groupingBy(Tag::getTarget));

        return target -> tagsByTarget.get(target.normalized());
    }

    public TagLoader getTableTagLoaderForSchema(String schema) {
        List<Tag> allByType = tagRepository
            .findAllByTypeAndSchemaNameAndEnvironmentName("table", schema, environment.name());
        Map<TagTarget,List<Tag>> tagsByTarget = allByType.stream()
            .collect(Collectors.groupingBy(Tag::getTarget));

        return target -> tagsByTarget.get(target.normalized());
    }
}
