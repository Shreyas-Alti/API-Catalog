package com.apicatalog.service;

import com.apicatalog.dto.*;
import com.apicatalog.model.ApiEndpoint;
import com.apicatalog.model.ExtractedApi;
import com.apicatalog.model.Repository;
import com.apicatalog.config.LlmProperties;
import com.apicatalog.repository.RepositoryRepo;
import com.apicatalog.service.mcp.McpToolRegistrationService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.List;

@Service
public class RepositoryService {

    private final CloneService                  cloneService;
    private final ExtractionService             extractionService;
    private final EndpointEnrichmentService     enrichmentService;
    private final RepositoryRepo                repositoryRepo;
    private final McpToolRegistrationService    mcpRegistration;
    private final LlmProperties                 llmProperties;

    public RepositoryService(CloneService cloneService,
                             ExtractionService extractionService,
                             EndpointEnrichmentService enrichmentService,
                             RepositoryRepo repositoryRepo,
                             McpToolRegistrationService mcpRegistration,
                             LlmProperties llmProperties) {
        this.cloneService       = cloneService;
        this.extractionService  = extractionService;
        this.enrichmentService  = enrichmentService;
        this.repositoryRepo     = repositoryRepo;
        this.mcpRegistration    = mcpRegistration;
        this.llmProperties      = llmProperties;
    }

    // ── Submit: clone → detect → extract → enrich ────────────────────────────

    public SubmitResponse submit(SubmitRequest request) {
        String repoName = extractRepoName(request.getUrl());
        CloneService.CloneResult cloneResult = null;
        try {
            cloneResult = cloneService.clone(request.getUrl());
            Path tempDir  = cloneResult.path();
            String commitSha = cloneResult.commitSha();

            String framework = extractionService.detectFramework(tempDir);
            boolean supported = !"Unsupported".equals(framework);
            List<ExtractedApi> apis = supported ? extractionService.extract(tempDir) : List.of();

            // LLM enrichment (no-op if llm.enabled = false)
            if (!apis.isEmpty()) {
                enrichmentService.enrich(apis, tempDir, repoName);
            }

            return new SubmitResponse(repoName, request.getUrl(), request.getHostUrl(),
                    framework, supported, apis, commitSha,
                    llmProperties.isEnabled(),
                    request.getHostUrl() != null && !request.getHostUrl().isBlank());
        } finally {
            if (cloneResult != null) cloneService.cleanup(cloneResult.path());
        }
    }

    // ── Phase 6 ───────────────────────────────────────────────────────────────

    public RepositoryDetailDto save(SaveRequest request) {
        Repository repo = new Repository();
        repo.setName(request.getName());
        repo.setUrl(request.getUrl());
        repo.setHostUrl(request.getHostUrl());
        repo.setFramework(request.getFramework());
        repo.setCommitSha(request.getCommitSha());
        repo.setOpenapiDirty(true);
        populateEndpoints(repo, request.getApis());
        Repository saved = repositoryRepo.save(repo);
        mcpRegistration.registerForRepository(saved);
        return toDetailDto(saved);
    }

    public void delete(Long id) {
        if (!repositoryRepo.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Repository not found: " + id);
        }
        mcpRegistration.unregisterForRepository(id);
        repositoryRepo.deleteById(id);
    }

    public RepositoryDetailDto rescan(Long id) {
        Repository repo = repositoryRepo.findByIdWithEndpoints(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Repository not found: " + id));
        CloneService.CloneResult cloneResult = null;
        try {
            cloneResult = cloneService.clone(repo.getUrl());
            Path tempDir = cloneResult.path();
            String framework = extractionService.detectFramework(tempDir);
            boolean supported = !"Unsupported".equals(framework);
            List<ExtractedApi> apis = supported ? extractionService.extract(tempDir) : List.of();
            if (!apis.isEmpty()) enrichmentService.enrich(apis, tempDir, repo.getName());
            repo.setFramework(framework);
            repo.setCommitSha(cloneResult.commitSha());
            repo.setOpenapiDirty(true);
            populateEndpoints(repo, apis);
            Repository saved = repositoryRepo.save(repo);
            mcpRegistration.registerForRepository(saved);
            return toDetailDto(saved);
        } finally {
            if (cloneResult != null) cloneService.cleanup(cloneResult.path());
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
            // AI-enriched fields
            endpoint.setSummary(api.getSummary());
            endpoint.setRequestExample(api.getRequestExample());
            endpoint.setResponseExample(api.getResponseExample());
            endpoint.setAiGenerated(api.isAiGenerated());
            endpoint.setNeedsReview(false); // cleared when user explicitly saves
            endpoint.setLlmModel(api.getLlmModel());
            endpoint.setManuallyEdited(api.isManuallyEdited());
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
                    dto.setSummary(e.getSummary());
                    dto.setRequestExample(e.getRequestExample());
                    dto.setResponseExample(e.getResponseExample());
                    dto.setAiGenerated(e.isAiGenerated());
                    dto.setNeedsReview(e.isNeedsReview());
                    dto.setLlmModel(e.getLlmModel());
                    dto.setManuallyEdited(e.isManuallyEdited());
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
