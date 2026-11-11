package com.github.safetytrick.sqlexplorer.commentary;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

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

    private final TagRepository tagRepository;
    private final UserService userService;
    private final CommentaryEnvironment environment;
    private final TagTargetLookup targetLookup;

    public BulkTagService(TagRepository tagRepository, UserService userService,
                   CommentaryEnvironment environment, TagTargetLookup targetLookup) {
        this.tagRepository = tagRepository;
        this.userService = userService;
        this.environment = environment;
        this.targetLookup = targetLookup;
    }

    /**
     * @param name      the tag applied or removed
     * @param requested how many distinct paths were asked about
     * @param changed   how many were actually written or detached
     * @param unchanged how many already carried the tag (attach), or did not
     *                  (detach)
     */
    public record BulkResult(String name, int requested, int changed, int unchanged) { }

    /**
     * Attach {@code name} to every target that does not already carry it.
     *
     * <p>Takes targets rather than paths since sqlx-3ai. A path could not
     * express a name containing {@code /}, so one such row on a page would have
     * made the whole batch fail — the rows the user selected would stay
     * untagged and the reason would be one odd name among hundreds.
     */
    @Transactional
    public BulkResult attach(String name, List<TagTarget> targets, String type) {
        List<TagTarget> distinct = validatedTargets(name, targets, type);
        List<List<Tag>> existing = targetLookup.tagsFor(distinct);

        int attached = 0;
        for (int index = 0; index < distinct.size(); index++) {
            boolean alreadyTagged = existing.get(index).stream()
                .anyMatch(tag -> tag.getName().equals(name));
            if (alreadyTagged) {
                continue;
            }
            Tag tag = new Tag();
            tag.setAuthor(userService.currentUser());
            tag.setName(name);
            tag.setEnvironmentName(environment.name());
            tag.target(distinct.get(index));
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
    public BulkResult detach(String name, List<TagTarget> targets) {
        List<TagTarget> distinct = validatedTargets(name, targets, null);
        List<Integer> ids = targetLookup.tagsFor(distinct).stream()
            .flatMap(List::stream)
            .filter(tag -> tag.getName().equals(name))
            .map(Tag::getId)
            .toList();

        // By id, resolved from the targets asked about — so nothing carrying the
        // same name outside this set can be touched, which is the property the
        // path-scoped delete existed to guarantee (sqlx-agl).
        tagRepository.deleteAllByIdInAndEnvironmentName(ids, environment.name());
        return new BulkResult(name, distinct.size(), ids.size(), distinct.size() - ids.size());
    }

    /**
     * Distinct paths, in the order given, with the name and size checked.
     *
     * <p>Deduplicated because a caller can legitimately send the same path
     * twice — a query matching under two ids, a table listed once per schema —
     * and the counts reported back have to mean "objects", not "list entries".
     */
    private static List<TagTarget> validatedTargets(String name, List<TagTarget> targets, String type) {
        String nameProblem = Names.tagNameProblem(name);
        if (nameProblem != null) {
            throw new BulkTagRequestException(nameProblem);
        }
        if (targets == null || targets.isEmpty()) {
            throw new BulkTagRequestException("at least one target is required");
        }
        List<TagTarget> checked = new java.util.ArrayList<>(targets.size());
        for (TagTarget target : targets) {
            try {
                TagTarget valid = target.validatedForTags();
                if (type != null && !type.equals(valid.type())) {
                    throw new BulkTagRequestException("type " + type
                        + " does not match a target addressing a " + valid.type());
                }
                checked.add(valid);
            } catch (IllegalArgumentException e) {
                throw new BulkTagRequestException(e.getMessage());
            }
        }
        List<TagTarget> distinct = List.copyOf(new LinkedHashSet<>(checked));
        if (distinct.size() > MAX_PATHS) {
            throw new BulkTagRequestException("too many targets: " + distinct.size()
                + " exceeds the limit of " + MAX_PATHS
                + ". Nothing was changed — narrow the set and try again.");
        }
        return distinct;
    }

    /** The kind of thing a path names, derived the same way a single tag does. */
    static String typeOf(String path) {
        return targetOf(path, null).type();
    }

    /**
     * What a path addresses, refusing one this format cannot represent.
     *
     * <p>An explicit type that disagrees with the address is refused rather than
     * preferred: it means the caller believes something untrue about what it is
     * tagging, and silently going with either answer files the tag somewhere
     * nobody will look for it.
     */
    static TagTarget targetOf(String path, String type) {
        TagPath parsed = TagPath.parse(path);
        if (parsed == null) {
            throw new BulkTagRequestException("could not derive type from path and none was given: " + path);
        }
        if (type != null && !type.equals(parsed.type())) {
            throw new BulkTagRequestException("type " + type + " does not match " + path
                + ", which addresses a " + parsed.type());
        }
        return parsed.toTarget();
    }
}
