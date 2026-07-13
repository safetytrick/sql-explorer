package com.github.safetytrick.sqlexplorer.commentary;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

@Service
public class TagsService implements TagLoader {

    private final TagRepository tagRepository;

    TagsService(TagRepository tagRepository) {
        this.tagRepository = tagRepository;
    }

    public List<Tag> findAllByNameAndType(String name, String type) {
        return this.tagRepository.findAllByNameAndType(name, type);
    }

    public List<Tag> findAllByType(String type) {
        return this.tagRepository.findAllByType(type);
    }

    @Override
    public List<Tag> findAllByPath(String path) {
        return this.tagRepository.findAllByPath(path);
    }

    public TagLoader getBulkTagLoaderByType(String type) {
        List<Tag> allByType = tagRepository.findAllByType(type);
        Map<String,List<Tag>> tagsByPath = allByType.stream()
            .collect(Collectors.groupingBy(Tag::getPath));

        return tagsByPath::get;
    }

    public TagLoader getBulkTagLoaderByTypeAndPathPrefix(String type, String path) {
        List<Tag> allByType = tagRepository.findAllByTypeAndPathStartsWith(type, path);
        Map<String,List<Tag>> tagsByPath = allByType.stream()
            .collect(Collectors.groupingBy(Tag::getPath));

        return tagsByPath::get;
    }
}
