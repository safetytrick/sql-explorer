package com.github.safetytrick.sqlexplorer.commentary;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.safetytrick.sqlexplorer.auth.UserService;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

/**
 * Applying or removing one tag across a whole set of paths.
 *
 * <p>Tags stopped being decorative when scope filtering started travelling in
 * the URL: a tag is now how you carve out what you are working on, and carving
 * one out a row at a time is why this exists. The {@code system} tag that
 * subtracts MySQL's own schemas covers 48 tables (sqlx-agl, sqlx-uxo).
 *
 * <p>SAFE TO RE-RUN. Attaching is keyed on (name, path), so a path that already
 * carries the tag is left alone rather than duplicated, and overlapping sets do
 * not compound. The result says how many were newly attached and how many were
 * already there, because "tagged 0" over a set you expected to change is
 * information, not a failure.
 *
 * <p>THE CAP REJECTS RATHER THAN TRUNCATES. Silently stopping at a limit is the
 * worst available outcome: the caller would believe the whole set was written.
 * A set over {@link #MAX_PATHS} is refused, and the message names the limit.
 */
@Service
public class BulkTagService {

    /**
     * The most paths one call may touch.
     *
     * <p>Sized above the corpus on purpose. Measured on the regression
     * environment: 29 schemas, 308 tables, ~4,300 query profiles. A cap below
     * the full unfiltered set would make "tag everything that matched" fail on
     * the most obvious use of the feature, which is worse than no cap at all —
     * this is a backstop against a runaway client, not a quota.
     */
    public static final int MAX_PATHS = 25_000;

    /** Tag names are used in URLs and filenames; keep them boring. */
    private static final Pattern TAG_NAME = Pattern.compile("[\\w-]+");

    /** The path segment before the last one names what kind of thing is tagged. */
    private static final Pattern PATH_TYPE = Pattern.compile("([^/]+)/[^/]+$");

    private final TagRepository tagRepository;
    private final UserService userService;

    public BulkTagService(TagRepository tagRepository, UserService userService) {
        this.tagRepository = tagRepository;
        this.userService = userService;
    }

    /**
     * @param name      the tag applied or removed
     * @param requested how many distinct paths were asked about
     * @param changed   how many were actually written or detached
     * @param unchanged how many already carried the tag (attach), or did not
     *                  (detach)
     */
    public record BulkResult(String name, int requested, int changed, int unchanged) { }

    /** Attach {@code name} to every path that does not already carry it. */
    @Transactional
    public BulkResult attach(String name, List<String> paths, String type) {
        List<String> distinct = validated(name, paths);
        Map<String, List<Tag>> existing = tagRepository.findAllByPathInGroupedByPath(distinct);

        int attached = 0;
        for (String path : distinct) {
            boolean alreadyTagged = existing.getOrDefault(path, List.of()).stream()
                .anyMatch(tag -> tag.getName().equals(name));
            if (alreadyTagged) {
                continue;
            }
            Tag tag = new Tag();
            tag.setAuthor(userService.currentUser());
            tag.setName(name);
            tag.setPath(path);
            tag.setType(type == null ? typeOf(path) : type);
            tagRepository.save(tag);
            attached++;
        }
        return new BulkResult(name, distinct.size(), attached, distinct.size() - attached);
    }

    /**
     * Detach {@code name} from these paths and nowhere else.
     *
     * <p>A bulk attach without this is a trap: {@code DELETE /tag/{id}} works
     * one association at a time, so undoing 500 rows would be 500 calls the UI
     * has no way to make.
     */
    @Transactional
    public BulkResult detach(String name, List<String> paths) {
        List<String> distinct = validated(name, paths);
        int carrying = (int) tagRepository.findAllByPathInGroupedByPath(distinct).values().stream()
            .flatMap(List::stream)
            .filter(tag -> tag.getName().equals(name))
            .count();

        tagRepository.deleteAllByNameAndPathIn(name, distinct);
        return new BulkResult(name, distinct.size(), carrying, distinct.size() - carrying);
    }

    /**
     * Distinct paths, in the order given, with the name and size checked.
     *
     * <p>Deduplicated because a caller can legitimately send the same path
     * twice — a query matching under two ids, a table listed once per schema —
     * and the counts reported back have to mean "objects", not "list entries".
     */
    private static List<String> validated(String name, List<String> paths) {
        if (name == null || !TAG_NAME.matcher(name).matches()) {
            throw new BulkTagRequestException(
                "name must only contain letters, numbers, underscores and hyphens");
        }
        if (paths == null || paths.isEmpty()) {
            throw new BulkTagRequestException("at least one path is required");
        }
        List<String> distinct = List.copyOf(new LinkedHashSet<>(paths));
        if (distinct.size() > MAX_PATHS) {
            throw new BulkTagRequestException("too many paths: " + distinct.size()
                + " exceeds the limit of " + MAX_PATHS
                + ". Nothing was changed — narrow the set and try again.");
        }
        return distinct;
    }

    /** The kind of thing a path names, derived the same way a single tag does. */
    static String typeOf(String path) {
        Matcher matcher = PATH_TYPE.matcher(path);
        if (!matcher.find()) {
            throw new BulkTagRequestException("could not derive type from path and none was given: " + path);
        }
        return matcher.group(1);
    }
}
