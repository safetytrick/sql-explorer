package com.github.safetytrick.sqlexplorer.commentary;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import com.github.safetytrick.sqlexplorer.auth.UserService;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

@Service
public class BulkTagService {

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

    public record BulkResult(String name, int requested, int changed, int unchanged) { }

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

    @Transactional
    public BulkResult detach(String name, List<TagTarget> targets) {
        List<TagTarget> distinct = validatedTargets(name, targets, null);
        List<Integer> ids = targetLookup.tagsFor(distinct).stream()
            .flatMap(List::stream)
            .filter(tag -> tag.getName().equals(name))
            .map(Tag::getId)
            .toList();

        tagRepository.deleteAllByIdInAndEnvironmentName(ids, environment.name());
        return new BulkResult(name, distinct.size(), ids.size(), distinct.size() - ids.size());
    }

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

    static String typeOf(String path) {
        return targetOf(path, null).type();
    }

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
