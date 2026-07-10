package com.apicatalog.service;

import com.apicatalog.dto.SearchResultDto;
import com.apicatalog.model.ApiEndpoint;
import com.apicatalog.model.Repository;
import com.apicatalog.repository.ApiEndpointRepo;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class SearchService {

    private final ApiEndpointRepo apiEndpointRepo;

    public SearchService(ApiEndpointRepo apiEndpointRepo) {
        this.apiEndpointRepo = apiEndpointRepo;
    }

    public List<SearchResultDto> search(String repo, String framework, String method, String path) {
        // Treat blank strings as null so the JPQL IS NULL check works correctly
        String repoParam      = blank(repo)      ? null : repo;
        String frameworkParam = blank(framework) ? null : framework;
        String methodParam    = blank(method)    ? null : method;
        String pathParam      = blank(path)      ? null : path;

        return apiEndpointRepo
                .search(repoParam, frameworkParam, methodParam, pathParam)
                .stream()
                .map(this::toDto)
                .toList();
    }

    private SearchResultDto toDto(ApiEndpoint e) {
        Repository r = e.getRepository();
        return new SearchResultDto(
                e.getId(), e.getMethod(), e.getPath(),
                e.getDescription(), e.getController(), e.getHandler(),
                r.getId(), r.getName(), r.getUrl(), r.getFramework());
    }

    private boolean blank(String s) {
        return !StringUtils.hasText(s);
    }
}
