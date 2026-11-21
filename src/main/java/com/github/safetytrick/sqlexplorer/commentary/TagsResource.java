package com.github.safetytrick.sqlexplorer.commentary;

import com.github.safetytrick.sqlexplorer.environment.EnvironmentScoped;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.github.safetytrick.sqlexplorer.auth.UserService;
import jakarta.transaction.Transactional;
import org.springframework.http.HttpStatus;
import lombok.Data;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@io.swagger.v3.oas.annotations.tags.Tag(name="Tags")
@EnvironmentScoped
@RestController
class TagsResource {
    private final TagRepository tagRepository;
    private final UserService userService;
    private final BulkTagService bulkTagService;
    private final CommentaryEnvironment environment;
    private final TagTargetLookup targetLookup;

    public TagsResource(TagRepository tagRepository, UserService userService, BulkTagService bulkTagService,
                        CommentaryEnvironment environment, TagTargetLookup targetLookup) {
        this.environment = environment;
        this.targetLookup = targetLookup;
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

    @PostMapping("/tags/targets")
    public List<List<Tag>> tagsForTargets(@RequestBody List<TagTarget> targets) {
        try {
            return targetLookup.tagsFor(targets.stream().map(TagTarget::validatedForTags).toList());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/tags")
    public Tag createTag(@RequestBody CreateTag createTag) {
        Objects.requireNonNull(createTag.name, "name is required");

        String nameProblem = Names.tagNameProblem(createTag.name);
        if (nameProblem != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, nameProblem);
        }

        TagTarget path;
        try {
            path = targetOf(createTag);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }

        if (createTag.getType() != null && !createTag.getType().equals(path.type())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "type " + createTag.getType() + " does not match the target, "
                    + "which addresses a " + path.type());
        }

        Optional<Tag> existingTag = tagRepository
            .findByNameAndEnvironmentNameAndTypeAndSchemaNameAndTableNameAndColumnNameAndQueryDigest(
                createTag.name, environment.name(), path.type(),
                path.schema(), path.table(), path.column(), path.digest());
        if (existingTag.isPresent()) {
            return existingTag.get();
        }

        Tag tag = new Tag();
        tag.setAuthor(userService.currentUser());
        tag.setName(createTag.name);
        tag.setEnvironmentName(environment.name());
        tag.target(path);

        tag = tagRepository.save(tag);

        return tag;
    }

    @PostMapping("/tags/bulk")
    public BulkTagService.BulkResult bulkTag(@RequestBody BulkTag request) {
        return bulkTagService.attach(request.getName(), targetsOf(request), request.getType());
    }

    @PostMapping("/tags/bulk/remove")
    public BulkTagService.BulkResult bulkUntag(@RequestBody BulkTag request) {
        return bulkTagService.detach(request.getName(), targetsOf(request));
    }

    @DeleteMapping("/tag/{id}")
    @Transactional
    public void deleteTag(@PathVariable int id) {
        if (tagRepository.deleteByIdAndEnvironmentName(id, environment.name()) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "no tag " + id + " in " + environment.name());
        }
    }

    static List<TagTarget> targetsOf(BulkTag request) {
        if (request.getTargets() != null && !request.getTargets().isEmpty()) {
            return request.getTargets();
        }
        if (request.getPaths() == null) {
            return List.of();
        }
        return request.getPaths().stream()
            .map(path -> TagPath.require(path).toTarget())
            .toList();
    }

    static TagTarget targetOf(CreateTag createTag) {
        if (createTag.getTarget() != null) {
            return createTag.getTarget().validatedForTags();
        }
        if (createTag.getPath() == null) {
            throw new IllegalArgumentException("a tag needs a target or a path");
        }
        return TagPath.require(createTag.getPath()).toTarget().validatedForTags();
    }

    @Data
    public static class BulkTag {
        private String name;

        private List<TagTarget> targets;

        private List<String> paths;

        private String type;
    }

    @Data
    public static class CreateTag {
        private String name;

        private TagTarget target;

        private String path;

        private String type;
    }
}
