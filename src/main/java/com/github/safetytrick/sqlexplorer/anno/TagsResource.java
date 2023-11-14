package com.github.safetytrick.sqlexplorer.anno;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.safetytrick.sqlexplorer.auth.UserService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.Data;

@RestController
public class TagsResource {
    private final TagRepository tagRepository;
    private final UserService userService;
    private final Pattern tagPattern = Pattern.compile("[\\w-]+");
    private final Pattern pathTypePattern = Pattern.compile("([^/]+)/[^/]+$");

    public TagsResource(TagRepository tagRepository, UserService userService) {
        this.tagRepository = tagRepository;
        this.userService = userService;
    }

    @GetMapping("/api/sql-explorer/tags/names")
    public List<String> tagAutocomplete(@RequestParam(name="value") String value) {
        String escapedValue = value.replaceAll("\\\\", "\\\\")
            .replaceAll("_", "\\_")
            .replaceAll("%", "\\%");

        return tagRepository.findTagNamesStartingWith(escapedValue);
    }

    @GetMapping("/api/sql-explorer/tags/type/{type}")
    public List<String> tagsByType(@PathVariable("type") String type) {
        return tagRepository.findTagNamesByType(type);
    }

    @GetMapping("/api/sql-explorer/tags/path")
    public List<Tag> tagsForPath(@RequestParam(name="path") String path) {
        return tagRepository.findAllByPath(path);
    }

    @PostMapping("/api/sql-explorer/tags/path")
    public Map<String, List<Tag>> tagsForPaths(@RequestBody List<String> paths) {
        return tagRepository.findAllByPathInGroupedByPath(paths);
    }

    @PostMapping("/api/sql-explorer/tags")
    public Tag createTag(@RequestBody CreateTag createTag) {
        Objects.requireNonNull(createTag.name, "name is required");
        Objects.requireNonNull(createTag.path, "path is required");

        if (!tagPattern.matcher(createTag.name).matches()) {
            throw new IllegalArgumentException("name must only container letters, numbers, underscores and hyphens");
        }


        Optional<Tag> existingTag = tagRepository.findByNameAndPath(createTag.name, createTag.path);
        if (existingTag.isPresent()) {
            return existingTag.get();
        }

        Tag tag = new Tag();
        tag.setAuthor(userService.currentUser());
        tag.setName(createTag.name);
        tag.setPath(createTag.path);

        if (createTag.getType() == null) {
            Matcher pathTypeMatcher = pathTypePattern.matcher(createTag.getPath());
            if (!pathTypeMatcher.find()) {
                throw new IllegalArgumentException("could not derive type and no type was specified");
            }
            tag.setType(pathTypeMatcher.group(1));
        } else {
            tag.setType(createTag.getType());
        }

        tag = tagRepository.save(tag);

        return tag;
    }

    @DeleteMapping("/api/sql-explorer/tag/{id}")
    public void deleteTag(@PathVariable("id") int id) {
        tagRepository.deleteById(id);
    }

    @Data
    public static class CreateTag {
        private String name;
        private String path;
        private String type;
    }
}
