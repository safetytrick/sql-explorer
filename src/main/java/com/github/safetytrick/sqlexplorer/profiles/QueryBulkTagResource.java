package com.github.safetytrick.sqlexplorer.profiles;

import com.github.safetytrick.sqlexplorer.environment.EnvironmentScoped;
import java.util.List;

import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.commentary.BulkTagService;
import com.github.safetytrick.sqlexplorer.commentary.TagTarget;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tagging every query a workbench search matched.
 *
 * <p>The list pages enumerate their own paths and use {@code POST /tags/bulk}
 * directly; they have already loaded every row they display. The workbench
 * cannot: its filtering is server-side and paged, the client holds one page,
 * and the match set runs to thousands. So the server resolves the set, exactly
 * as {@code POST /graph/v2/search} already does for the graph (sqlx-agl).
 *
 * <p>Lives here rather than beside the other tag endpoints so the dependency
 * runs one way. {@code profiles} already knows about {@code commentary} —
 * queries carry tags — and reaching back the other way to run a search would
 * make the two packages mutually dependent for no benefit.
 *
 * <p>THE COUNT AND THE WRITE COME FROM ONE PLACE. Both endpoints resolve
 * through {@link QuerySearchService#matchingProfiles}, which is also what the
 * workbench's own total is computed from, so a confirmation saying "tag 1,847
 * queries" cannot disagree with the page behind it or with what is written.
 */
@Tag(name = "Tags")
@EnvironmentScoped
@RestController
class QueryBulkTagResource {

    private final QuerySearchService querySearchService;
    private final BulkTagService bulkTagService;

    QueryBulkTagResource(QuerySearchService querySearchService, BulkTagService bulkTagService) {
        this.querySearchService = querySearchService;
        this.bulkTagService = bulkTagService;
    }

    /**
     * How many queries a search matches, and whether that is over the cap.
     *
     * <p>What a confirmation is built from. Nothing is written, and it resolves
     * the set the same way the write does — a count from anywhere else would
     * eventually name a number the write does not honour.
     *
     * @param count  how many queries would be affected
     * @param limit  the most one call may touch
     * @param capped whether the set exceeds it, in which case the write is
     *               refused rather than truncated
     */
    public record SearchTagPreview(int count, int limit, boolean capped) { }

    @PostMapping("/tags/search/preview")
    public SearchTagPreview preview(@RequestBody(required = false) QuerySearchRequest request) {
        int count = targets(request).size();
        return new SearchTagPreview(count, BulkTagService.MAX_PATHS, count > BulkTagService.MAX_PATHS);
    }

    /** Attach or detach one tag across every query the search matched. */
    @PostMapping("/tags/search")
    public BulkTagService.BulkResult tagSearch(@RequestBody SearchTag request) {
        List<TagTarget> targets = targets(request.getSearch());
        return request.isRemove()
            ? bulkTagService.detach(request.getName(), targets)
            : bulkTagService.attach(request.getName(), targets, QUERY_TYPE);
    }

    /**
     * The tag path for every matching query.
     *
     * <p>Keyed on the digest, which is what a query target means everywhere else
     * in the tagging API. A profile with no digest is skipped: nothing would
     * address it, and inventing a target would attach a tag nothing could ever
     * find or remove.
     */
    private List<TagTarget> targets(QuerySearchRequest request) {
        return querySearchService.matchingProfiles(request).stream()
            .map(SqlProfile::getDigest)
            .filter(digest -> digest != null && !digest.isBlank())
            .distinct()
            .map(TagTarget::ofQuery)
            .toList();
    }

    /** What a query association is called; the path has no plural segment. */
    private static final String QUERY_TYPE = "query";

    @Data
    public static class SearchTag {
        private String name;
        private QuerySearchRequest search;
        /** Detach instead of attach. Same set, same resolution, opposite write. */
        private boolean remove;
    }
}
