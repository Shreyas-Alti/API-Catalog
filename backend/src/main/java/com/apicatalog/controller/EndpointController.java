package com.apicatalog.controller;

import com.apicatalog.config.LlmProperties;
import com.apicatalog.model.ApiEndpoint;
import com.apicatalog.model.ExtractedApi;
import com.apicatalog.model.Repository;
import com.apicatalog.repository.ApiEndpointRepo;
import com.apicatalog.service.CloneService;
import com.apicatalog.service.EndpointEnrichmentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Per-endpoint operations — currently just the regenerate action.
 */
@RestController
@RequestMapping("/api/endpoints")
public class EndpointController {

    private final ApiEndpointRepo        endpointRepo;
    private final EndpointEnrichmentService enrichmentService;
    private final CloneService           cloneService;
    private final LlmProperties          llmProperties;

    public EndpointController(ApiEndpointRepo endpointRepo,
                              EndpointEnrichmentService enrichmentService,
                              CloneService cloneService,
                              LlmProperties llmProperties) {
        this.endpointRepo       = endpointRepo;
        this.enrichmentService  = enrichmentService;
        this.cloneService       = cloneService;
        this.llmProperties      = llmProperties;
    }

    /**
     * POST /api/endpoints/{id}/regenerate[?force=true]
     *
     * Re-runs LLM enrichment for one endpoint, ignoring the "skip if description present" rule.
     * Requires force=true if the endpoint has been manually edited; otherwise returns 409.
     */
    @PostMapping("/{id}/regenerate")
    public ResponseEntity<Void> regenerate(
            @PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean force) {

        if (!llmProperties.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "LLM enrichment is disabled (llm.enabled=false)");
        }

        ApiEndpoint ep = endpointRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Endpoint not found: " + id));

        if (ep.isManuallyEdited() && !force) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This endpoint has manual edits. Add ?force=true to overwrite them.");
        }

        Repository repo = ep.getRepository();

        // Re-clone to get source context
        CloneService.CloneResult cloneResult = cloneService.clone(repo.getUrl());
        try {
            // Build a single-item ExtractedApi from the entity to reuse EnrichmentService
            ExtractedApi api = toExtractedApi(ep);
            // Force re-enrich by temporarily clearing description
            String savedDescription = api.getDescription();
            api.setDescription(null);

            enrichmentService.enrich(List.of(api), cloneResult.path(), repo.getName());

            // If LLM produced nothing, restore the old description
            if (api.getDescription() == null) api.setDescription(savedDescription);

            // Merge back into entity
            ep.setSummary(api.getSummary());
            ep.setDescription(api.getDescription());
            if (api.getTags() != null) ep.setTags(api.getTags());
            ep.setRequestExample(api.getRequestExample());
            ep.setResponseExample(api.getResponseExample());
            ep.setAiGenerated(true);
            ep.setNeedsReview(true);
            ep.setLlmModel(api.getLlmModel());
            // Parameters and field descriptions
            ep.setParameters(api.getParameters());
            ep.setRequestBodyFields(api.getRequestBodyFields());
            ep.setResponseBodyFields(api.getResponseBodyFields());

            // Mark the parent repo dirty
            repo.setOpenapiDirty(true);
            endpointRepo.save(ep);

            return ResponseEntity.noContent().build();
        } finally {
            cloneService.cleanup(cloneResult.path());
        }
    }

    private ExtractedApi toExtractedApi(ApiEndpoint ep) {
        ExtractedApi api = new ExtractedApi();
        api.setMethod(ep.getMethod());
        api.setPath(ep.getPath());
        api.setDescription(ep.getDescription());
        api.setController(ep.getController());
        api.setHandler(ep.getHandler());
        api.setTags(ep.getTags());
        api.setParameters(ep.getParameters());
        api.setRequestBodyType(ep.getRequestBodyType());
        api.setRequestBodyFields(ep.getRequestBodyFields());
        api.setResponseBodyType(ep.getResponseBodyType());
        api.setResponseBodyFields(ep.getResponseBodyFields());
        api.setStatusCodes(ep.getStatusCodes());
        api.setSourceFile(ep.getSourceFile());
        api.setSourceLine(ep.getSourceLine());
        api.setSummary(ep.getSummary());
        return api;
    }
}
