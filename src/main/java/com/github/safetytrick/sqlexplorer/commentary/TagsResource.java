package com.github.safetytrick.sqlexplorer.commentary;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.safetytrick.sqlexplorer.auth.UserService;
import lombok.Data;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@io.swagger.v3.oas.annotations.tags.Tag(name="Tags")
@RestController
class TagsResource {
    private final TagRepository tagRepository;
    private final UserService userService;
    private final BulkTagService bulkTagService;
    private final CommentaryEnvironment environment;
    private final Pattern tagPattern = Pattern.compile("[\\w-]+");
    private final Pattern pathTypePattern = Pattern.compile("([^/]+)/[^/]+$");

    public TagsResource(TagRepository tagRepository, UserService userService, BulkTagService bulkTagService,
                        CommentaryEnvironment environment) {
        this.environment = environment;
        this.tagRepository = tagRepository;
        this.userService = userService;
        this.bulkTagService = bulkTagService;
    }

    @GetMapping("/tags/names")
    public List<String> tagAutocomplete(@RequestParam String value) {
        String escapedValue = value.replaceAll("\\\\", "\\\\")
            .replaceAll("_", "\\_")
            .replaceAll("%", "\\%");

        return tagRepository.findTagNamesStartingWith(escapedValue, environment.name());
    }

    @GetMapping("/tags/type/{type}")
    public List<String> tagsByType(@PathVariable String type) {
        return tagRepository.findTagNamesByType(type, environment.name());
    }

    @GetMapping("/tags/path")
    public List<Tag> tagsForPath(@RequestParam String path) {
        return tagRepository.findAllByPathAndEnvironmentName(path, environment.name());
    }

    @PostMapping("/tags/path")
    public Map<String, List<Tag>> tagsForPaths(@RequestBody List<String> paths) {
        return tagRepository.findAllByPathInGroupedByPath(paths, environment.name());
    }

    @PostMapping("/tags")
    public Tag createTag(@RequestBody CreateTag createTag) {
        Objects.requireNonNull(createTag.name, "name is required");
        Objects.requireNonNull(createTag.path, "path is required");

        if (!tagPattern.matcher(createTag.name).matches()) {
            throw new IllegalArgumentException("name must only container letters, numbers, underscores and hyphens");
        }


        Optional<Tag> existingTag = tagRepository
            .findByNameAndPathAndEnvironmentName(createTag.name, createTag.path, environment.name());
        if (existingTag.isPresent()) {
            return existingTag.get();
        }

        Tag tag = new Tag();
        tag.setAuthor(userService.currentUser());
        tag.setName(createTag.name);
        tag.setPath(createTag.path);
        tag.setEnvironmentName(environment.name());

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

    /**
     * Attach one tag to every path given.
     *
     * <p>The paths are the set, and the caller enumerates them — right for the
     * list pages, which have already loaded every row they are showing and so
     * know exactly what "the current result set" means. The query workbench
     * filters server-side over thousands of rows and uses
     * {@code POST /tags/search} instead (sqlx-agl).
     */
    @PostMapping("/tags/bulk")
    public BulkTagService.BulkResult bulkTag(@RequestBody BulkTag request) {
        return bulkTagService.attach(request.getName(), request.getPaths(), request.getType());
    }

    /** Remove one tag from every path given, and from nowhere else. */
    @PostMapping("/tags/bulk/remove")
    public BulkTagService.BulkResult bulkUntag(@RequestBody BulkTag request) {
        return bulkTagService.detach(request.getName(), request.getPaths());
    }

    @DeleteMapping("/tag/{id}")
    public void deleteTag(@PathVariable int id) {
        tagRepository.deleteById(id);
    }

    @Data
    public static class BulkTag {
        private String name;
        private List<String> paths;
        /** Derived from each path when absent, as a single tag does. */
        private String type;
    }

    @Data
    public static class CreateTag {
        private String name;
        private String path;
        private String type;
    }
}
