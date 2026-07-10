package com.apicatalog.controller;

import com.apicatalog.dto.SearchResultDto;
import com.apicatalog.service.SearchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    /**
     * GET /api/search?repo=&framework=&method=&path=
     * All parameters are optional. Omitting or leaving blank a parameter means "no filter on that field".
     */
    @GetMapping
    public ResponseEntity<List<SearchResultDto>> search(
            @RequestParam(required = false) String repo,
            @RequestParam(required = false) String framework,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) String path) {

        return ResponseEntity.ok(searchService.search(repo, framework, method, path));
    }
}
