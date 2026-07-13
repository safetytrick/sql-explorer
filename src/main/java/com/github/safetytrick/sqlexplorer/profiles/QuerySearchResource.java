package com.github.safetytrick.sqlexplorer.profiles;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Query")
@RestController
class QuerySearchResource {

    private final QuerySearchService querySearchService;

    QuerySearchResource(QuerySearchService querySearchService) {
        this.querySearchService = querySearchService;
    }

    /**
     * Search the active environment's analyzed queries. POST (not GET) because
     * the filter set is a structured document; an absent body returns the
     * first page of everything, worst average time first.
     */
    @PostMapping("/queries/search")
    public QuerySearchResult search(@RequestBody(required = false) QuerySearchRequest request) {
        return querySearchService.search(request);
    }
}
