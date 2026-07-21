package com.apicatalog.service;

import com.apicatalog.dto.*;
import com.apicatalog.model.ApiEndpoint;
import com.apicatalog.model.ExtractedApi;
import com.apicatalog.model.Repository;
import com.apicatalog.repository.RepositoryRepo;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.List;

@Service
public class RepositoryService {

    private final CloneService cloneService;
    private final ExtractionService extractionService;
    private final RepositoryRepo repositoryRepo;

    public RepositoryService(CloneService cloneService, ExtractionService extractionService,
                             RepositoryRepo repositoryRepo) {
        this.cloneService = cloneService;
        this.extractionService = extractionService;
        this.repositoryRepo = repositoryRepo;
    }

    // ── Phase 2/3/4 ──────────────────────────────────────────────────────────

    public SubmitResponse submit(SubmitRequest request) {
        String repoName = extractRepoName(request.getUrl());
        Path tempDir = null;
        try {
            tempDir = cloneService.clone(request.getUrl());
            String framework = extractionService.detectFramework(tempDir);
            boolean supported = !"Unsupported".equals(framework);
            List<ExtractedApi> apis = supported ? extractionService.extract(tempDir) : List.of();
            return new SubmitResponse(repoName, request.getUrl(), request.getHostUrl(), framework, supported, apis);
        } finally {
            cloneService.cleanup(tempDir);
        }
    }

    // ── Phase 6 ───────────────────────────────────────────────────────────────

    public RepositoryDetailDto save(SaveRequest request) {
        Repository repo = new Repository();
        repo.setName(request.getName());
        repo.setUrl(request.getUrl());
        repo.setHostUrl(request.getHostUrl());
        repo.setFramework(request.getFramework());
        populateEndpoints(repo, request.getApis());
        return toDetailDto(repositoryRepo.save(repo));
    }

    public void delete(Long id) {
        if (!repositoryRepo.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Repository not found: " + id);
        }
        repositoryRepo.deleteById(id);
    }

    public RepositoryDetailDto rescan(Long id) {
        Repository repo = repositoryRepo.findByIdWithEndpoints(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Repository not found: " + id));
        Path tempDir = null;
        try {
            tempDir = cloneService.clone(repo.getUrl());
            String framework = extractionService.detectFramework(tempDir);
            boolean supported = !"Unsupported".equals(framework);
            List<ExtractedApi> apis = supported ? extractionService.extract(tempDir) : List.of();
            repo.setFramework(framework);
            populateEndpoints(repo, apis);
            return toDetailDto(repositoryRepo.save(repo));
        } finally {
            cloneService.cleanup(tempDir);
        }
    }

    public List<RepositorySummaryDto> listAll() {
        return repositoryRepo.findAllWithEndpoints().stream()
                .map(r -> new RepositorySummaryDto(
                        r.getId(), r.getName(), r.getUrl(), r.getHostUrl(), r.getFramework(),
                        r.getEndpoints().size(), r.getCreatedAt()))
                .toList();
    }

    public RepositoryDetailDto getById(Long id) {
        Repository repo = repositoryRepo.findByIdWithEndpoints(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Repository not found: " + id));
        return toDetailDto(repo);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void populateEndpoints(Repository repo, List<ExtractedApi> apis) {
        repo.getEndpoints().clear();
        if (apis == null) return;
        for (ExtractedApi api : apis) {
            ApiEndpoint endpoint = new ApiEndpoint();
            endpoint.setRepository(repo);
            endpoint.setMethod(api.getMethod());
            endpoint.setPath(api.getPath());
            endpoint.setDescription(api.getDescription());
            endpoint.setController(api.getController());
            endpoint.setHandler(api.getHandler());
            endpoint.setTags(api.getTags());
            endpoint.setParameters(api.getParameters());
            endpoint.setRequestBodyType(api.getRequestBodyType());
            endpoint.setRequestBodyFields(api.getRequestBodyFields());
            endpoint.setResponseBodyType(api.getResponseBodyType());
            endpoint.setResponseBodyFields(api.getResponseBodyFields());
            endpoint.setStatusCodes(api.getStatusCodes());
            endpoint.setSourceFile(api.getSourceFile());
            endpoint.setSourceLine(api.getSourceLine());
            repo.getEndpoints().add(endpoint);
        }
    }

    private RepositoryDetailDto toDetailDto(Repository repo) {
        List<EndpointDto> endpointDtos = repo.getEndpoints().stream()
                .map(e -> {
                    EndpointDto dto = new EndpointDto();
                    dto.setId(e.getId());
                    dto.setMethod(e.getMethod());
                    dto.setPath(e.getPath());
                    dto.setDescription(e.getDescription());
                    dto.setController(e.getController());
                    dto.setHandler(e.getHandler());
                    dto.setTags(e.getTags());
                    dto.setParameters(e.getParameters());
                    dto.setRequestBodyType(e.getRequestBodyType());
                    dto.setRequestBodyFields(e.getRequestBodyFields());
                    dto.setResponseBodyType(e.getResponseBodyType());
                    dto.setResponseBodyFields(e.getResponseBodyFields());
                    dto.setStatusCodes(e.getStatusCodes());
                    dto.setSourceFile(e.getSourceFile());
                    dto.setSourceLine(e.getSourceLine());
                    return dto;
                }).toList();

        return new RepositoryDetailDto(repo.getId(), repo.getName(), repo.getUrl(),
                repo.getHostUrl(), repo.getFramework(), repo.getCreatedAt(), endpointDtos);
    }

    private String extractRepoName(String url) {
        String name = url;
        if (name.endsWith(".git")) name = name.substring(0, name.length() - 4);
        int lastSlash = name.lastIndexOf('/');
        if (lastSlash >= 0) name = name.substring(lastSlash + 1);
        return name;
    }
}
