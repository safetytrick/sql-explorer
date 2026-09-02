package com.github.safetytrick.sqlexplorer.commentary;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

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
@RestController
class TagsResource {
    private final TagRepository tagRepository;
    private final UserService userService;
    private final BulkTagService bulkTagService;
    private final CommentaryEnvironment environment;
    private final TagTargetLookup targetLookup;
    private final Pattern tagPattern = Pattern.compile("[\\w-]+");

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

    /**
     * The tags on each of these targets, in the order asked.
     *
     * <p>Replaces keying a batch by path string, which could neither ask about
     * nor return a tag on a table whose name contains {@code /} — the storage
     * could hold one from sqlx-11v, but the API could not address it (sqlx-3ai).
     *
     * <p>Positional, so the caller zips it against its own rows. A duplicate
     * target is legitimate and gets the same answer at both positions.
     */
    @PostMapping("/tags/targets")
    public List<List<Tag>> tagsForTargets(@RequestBody List<TagTarget> targets) {
        try {
            return targetLookup.tagsFor(targets.stream().map(TagTarget::validated).toList());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/tags")
    public Tag createTag(@RequestBody CreateTag createTag) {
        // Not `path` specifically: a request may state its target either way,
        // and targetOf() below reports which forms are accepted (sqlx-3ai).
        Objects.requireNonNull(createTag.name, "name is required");

        // Both checks below reach the caller as a 400. A plain
        // IllegalArgumentException is a 500 here, which sends someone with a
        // fixable request to logs they cannot read — the same defect already
        // corrected on the bulk path (sqlx-agl) and the upload path (sqlx-djj);
        // this was the third instance.
        if (!tagPattern.matcher(createTag.name).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "name must only contain letters, numbers, underscores and hyphens");
        }

        // Validated before anything is looked up or written: the target is
        // client-supplied, and one that addresses nothing produces a row no lens
        // or filter can ever find (sqlx-0lj).
        TagTarget path;
        try {
            path = targetOf(createTag);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }

        if (createTag.getType() != null && !createTag.getType().equals(path.type())) {
            // Refused rather than silently preferred either way: a type that
            // disagrees with the address means the caller believes something
            // untrue about what it is tagging.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "type " + createTag.getType() + " does not match the target, "
                    + "which addresses a " + path.type());
        }

        Optional<Tag> existingTag = tagRepository
            .findByNameAndEnvironmentNameAndTypeAndSchemaNameAndTableNameAndQueryDigest(
                createTag.name, environment.name(), path.type(),
                path.schema(), path.table(), path.digest());
        if (existingTag.isPresent()) {
            return existingTag.get();
        }

        Tag tag = new Tag();
        tag.setAuthor(userService.currentUser());
        tag.setName(createTag.name);
        tag.setEnvironmentName(environment.name());
        // Type and target together, from the one parsed address: the path is no
        // longer stored, it is generated back from these (sqlx-11v).
        tag.target(path);

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
        return bulkTagService.attach(request.getName(), targetsOf(request), request.getType());
    }

    /** Remove one tag from every path given, and from nowhere else. */
    @PostMapping("/tags/bulk/remove")
    public BulkTagService.BulkResult bulkUntag(@RequestBody BulkTag request) {
        return bulkTagService.detach(request.getName(), targetsOf(request));
    }

    /**
     * Detach one association from the selected environment.
     *
     * <p>SCOPED, like every other read and write in this package. Association
     * ids are globally unique, so an unscoped delete could not remove the wrong
     * row by accident — but it would let an id belonging to one environment be
     * deleted while another was selected, and it was the last destructive path
     * that did not ask which corpus it was working in (sqlx-8p0).
     *
     * <p>A 404 rather than a silent no-op. The chip is removed optimistically by
     * the client, so an id this environment does not have has to come back as
     * something the client can react to; quietly reporting success would leave
     * the tag on screen until the next load and then bring it back.
     */
    @DeleteMapping("/tag/{id}")
    @Transactional
    public void deleteTag(@PathVariable int id) {
        if (tagRepository.deleteByIdAndEnvironmentName(id, environment.name()) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "no tag " + id + " in " + environment.name());
        }
    }

    /**
     * The target a request states, in either form.
     *
     * <p>The typed form wins when both are present; the path form stays
     * accepted because links and older clients still carry one, and because it
     * is what {@code /tags/bulk} sends until its own callers move over.
     */
    /** The targets a bulk request states, in either form. */
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
            return createTag.getTarget().validated();
        }
        if (createTag.getPath() == null) {
            throw new IllegalArgumentException("a tag needs a target or a path");
        }
        return TagPath.require(createTag.getPath()).toTarget().validated();
    }

    @Data
    public static class BulkTag {
        private String name;

        /**
         * What to tag, stated typed.
         *
         * <p>Preferred over {@link #paths}. A path cannot express a name
         * containing {@code /}, and one such row on a page would have made the
         * whole batch fail rather than just that row (sqlx-3ai).
         */
        private List<TagTarget> targets;

        /** The legacy addresses. Still accepted; cannot express every target. */
        private List<String> paths;

        /** Derived from each target when absent, as a single tag does. */
        private String type;
    }

    @Data
    public static class CreateTag {
        private String name;

        /**
         * What to tag, stated typed.
         *
         * <p>Preferred over {@link #path}, and the only form that can address a
         * name containing {@code /} (sqlx-3ai).
         */
        private TagTarget target;

        /** The legacy address. Still accepted; cannot express every target. */
        private String path;

        private String type;
    }
}
