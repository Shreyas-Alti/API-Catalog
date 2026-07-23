package com.apicatalog.service;

import com.apicatalog.config.LlmProperties;
import com.apicatalog.model.ApiField;
import com.apicatalog.model.ApiParameter;
import com.apicatalog.model.ExtractedApi;
import com.apicatalog.service.context.HandlerMethodExtractor;
import com.apicatalog.service.context.TypeIndexBuilder;
import com.apicatalog.service.llm.LlmClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * LLM enrichment service.
 *
 * Processes endpoints in file-level batches. Skips any endpoint that already
 * has a non-blank description (e.g. from a docstring or a previous run).
 * Writes summary, description, tags, examples, and field descriptions back
 * into the {@link ExtractedApi} objects. Never touches method/path/types.
 */
@Service
public class EndpointEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(EndpointEnrichmentService.class);

    private static final String SYSTEM_PROMPT = """
            You analyze API endpoints from source code and produce concise, accurate documentation.

            Rules (strictly enforced):
            - NEVER invent parameters, fields, or status codes not already present in the extracted data.
            - NEVER change method, path, controller, or type names.
            - summary: 2-5 words, action-oriented ("Create User", "List Orders").
            - description: 1-2 plain-English sentences explaining what the endpoint does.
            - tags: improve or confirm an existing tag if one is given; propose one only if none exists.
            - requestExample / responseExample: smallest valid JSON matching the extracted schema; omit optional fields.
            - parameter / field descriptions: a brief clause only when clearly inferable; omit if uncertain.
            - Return ONLY a valid JSON array — no prose, no markdown fences.
            """;

    private final LlmClient llmClient;
    private final LlmProperties llmProperties;
    private final HandlerMethodExtractor methodExtractor;
    private final TypeIndexBuilder typeIndexBuilder;
    private final ObjectMapper mapper;

    public EndpointEnrichmentService(LlmClient llmClient, LlmProperties llmProperties,
                                     HandlerMethodExtractor methodExtractor,
                                     TypeIndexBuilder typeIndexBuilder,
                                     ObjectMapper mapper) {
        this.llmClient      = llmClient;
        this.llmProperties  = llmProperties;
        this.methodExtractor = methodExtractor;
        this.typeIndexBuilder = typeIndexBuilder;
        this.mapper         = mapper;
    }

    /**
     * Enriches the list of extracted APIs in-place.
     * No-op if {@code llm.enabled = false}.
     *
     * @param apis       mutable list of extracted endpoints (not yet saved)
     * @param repoRoot   root path of the cloned repository
     * @param repoName   repository name, for context in the prompt
     */
    public void enrich(List<ExtractedApi> apis, Path repoRoot, String repoName) {
        if (!llmProperties.isEnabled() || apis == null || apis.isEmpty()) return;

        // Build type index once for the whole repo
        Map<String, Path> typeIndex = typeIndexBuilder.build(repoRoot);

        // Group by source file
        Map<String, List<IndexedApi>> byFile = new LinkedHashMap<>();
        for (int i = 0; i < apis.size(); i++) {
            ExtractedApi api = apis.get(i);
            String file = api.getSourceFile() != null ? api.getSourceFile() : "__unknown__";
            byFile.computeIfAbsent(file, k -> new ArrayList<>()).add(new IndexedApi(i, api));
        }

        for (Map.Entry<String, List<IndexedApi>> entry : byFile.entrySet()) {
            String relPath = entry.getKey();
            List<IndexedApi> group = entry.getValue();

            // Skip endpoints that already have a description
            List<IndexedApi> toEnrich = group.stream()
                    .filter(ia -> ia.api.getDescription() == null || ia.api.getDescription().isBlank())
                    .collect(Collectors.toList());

            if (toEnrich.isEmpty()) continue;

            try {
                Path absFile = repoRoot.resolve(relPath.replace('/', java.io.File.separatorChar));
                String prompt = buildPrompt(toEnrich, absFile, repoRoot, repoName, typeIndex);
                log.debug("Enriching {} endpoint(s) in {}", toEnrich.size(), relPath);

                String response = llmClient.complete(SYSTEM_PROMPT, prompt);
                mergeResponse(response, toEnrich, apis);
            } catch (Exception e) {
                log.warn("LLM enrichment failed for {}: {}", relPath, e.getMessage());
                // Degrade gracefully — leave endpoints without AI content
            }
        }
    }

    // ── Prompt construction ────────────────────────────────────

    private String buildPrompt(List<IndexedApi> items, Path absFile, Path repoRoot,
                                String repoName, Map<String, Path> typeIndex) {
        StringBuilder sb = new StringBuilder();
        sb.append("Repository: ").append(repoName).append("\n\n");

        for (IndexedApi item : items) {
            ExtractedApi api = item.api;
            sb.append("Endpoint ").append(item.index).append(":\n");
            sb.append("  Method:     ").append(api.getMethod()).append("\n");
            sb.append("  Path:       ").append(api.getPath()).append("\n");
            if (api.getController() != null) sb.append("  Controller: ").append(api.getController()).append("\n");
            if (api.getHandler() != null)    sb.append("  Handler:    ").append(api.getHandler()).append("\n");
            if (api.getTags() != null && !api.getTags().isEmpty())
                sb.append("  Tags (existing): ").append(String.join(", ", api.getTags())).append("\n");

            // Parameters summary
            if (api.getParameters() != null && !api.getParameters().isEmpty()) {
                sb.append("  Parameters:\n");
                for (ApiParameter p : api.getParameters()) {
                    if ("BODY".equals(p.getLocation())) continue;
                    sb.append("    - ").append(p.getName()).append(" (").append(p.getLocation())
                      .append(p.isRequired() ? ", required" : "").append("): ").append(p.getType()).append("\n");
                }
            }

            // Request body
            if (api.getRequestBodyType() != null) {
                sb.append("  Request body: ").append(api.getRequestBodyType()).append("\n");
                appendFields(sb, api.getRequestBodyFields());
            }

            // Response body
            if (api.getResponseBodyType() != null) {
                sb.append("  Response:     ").append(api.getResponseBodyType()).append("\n");
                appendFields(sb, api.getResponseBodyFields());
            }

            // Status codes
            if (api.getStatusCodes() != null && !api.getStatusCodes().isEmpty())
                sb.append("  Status codes: ").append(api.getStatusCodes()).append("\n");

            // Handler method source
            if (api.getSourceLine() != null && absFile != null && absFile.toFile().exists()) {
                String methodSrc = methodExtractor.extract(absFile, api.getSourceLine());
                if (!methodSrc.isBlank()) {
                    sb.append("  Handler source:\n```\n").append(methodSrc).append("\n```\n");
                }
            }

            // Request DTO source
            if (api.getRequestBodyType() != null) {
                String dtoSrc = typeIndexBuilder.extractTypeSource(api.getRequestBodyType(), typeIndex);
                if (!dtoSrc.isBlank())
                    sb.append("  Request DTO source:\n```\n").append(dtoSrc).append("\n```\n");
            }

            // Response DTO source
            if (api.getResponseBodyType() != null) {
                String dtoSrc = typeIndexBuilder.extractTypeSource(api.getResponseBodyType(), typeIndex);
                if (!dtoSrc.isBlank())
                    sb.append("  Response DTO source:\n```\n").append(dtoSrc).append("\n```\n");
            }

            sb.append("\n");
        }

        sb.append("Return a JSON array with one object per endpoint (matched by the \"id\" field, 0-based index). ")
          .append("Each object: { id, summary, description, tags, parameters (array of {name, description}), ")
          .append("requestBodyFields (array of {name, description}), responseBodyFields (array of {name, description}), ")
          .append("requestExample, responseExample }. ")
          .append("Omit any key you cannot fill confidently.");

        return sb.toString();
    }

    private void appendFields(StringBuilder sb, List<ApiField> fields) {
        if (fields == null || fields.isEmpty()) return;
        for (ApiField f : fields) {
            sb.append("    - ").append(f.getName()).append(": ").append(f.getType()).append("\n");
        }
    }

    // ── Response merging ───────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void mergeResponse(String json, List<IndexedApi> items, List<ExtractedApi> allApis) {
        List<Map<String, Object>> results;
        try {
            // Strip any accidental markdown fences
            String clean = json.replaceAll("```[a-z]*", "").trim();
            results = mapper.readValue(clean, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to parse LLM JSON response: {}", e.getMessage());
            return;
        }

        Map<Integer, IndexedApi> byIndex = new HashMap<>();
        for (IndexedApi ia : items) byIndex.put(ia.index, ia);

        for (Map<String, Object> obj : results) {
            int id = ((Number) obj.getOrDefault("id", -1)).intValue();
            IndexedApi ia = byIndex.get(id);
            if (ia == null) continue;

            ExtractedApi api = ia.api;

            if (obj.containsKey("summary"))     api.setSummary(str(obj, "summary"));
            if (obj.containsKey("description")) api.setDescription(str(obj, "description"));

            if (obj.containsKey("tags")) {
                Object tagsObj = obj.get("tags");
                if (tagsObj instanceof List<?> tagList) {
                    api.setTags(tagList.stream().map(Object::toString).collect(Collectors.toList()));
                }
            }

            if (obj.containsKey("requestExample"))
                api.setRequestExample((Map<String, Object>) obj.get("requestExample"));
            if (obj.containsKey("responseExample"))
                api.setResponseExample((Map<String, Object>) obj.get("responseExample"));

            // Merge per-parameter descriptions
            mergeFieldDescriptions(api.getParameters(),
                    (List<Map<String, Object>>) obj.get("parameters"));

            // Merge per-field descriptions
            mergeApiFieldDescriptions(api.getRequestBodyFields(),
                    (List<Map<String, Object>>) obj.get("requestBodyFields"));
            mergeApiFieldDescriptions(api.getResponseBodyFields(),
                    (List<Map<String, Object>>) obj.get("responseBodyFields"));

            api.setAiGenerated(true);
            api.setNeedsReview(true);
            api.setLlmModel(llmProperties.getModel());
        }
    }

    private void mergeFieldDescriptions(List<ApiParameter> params, List<Map<String, Object>> enriched) {
        if (params == null || enriched == null) return;
        Map<String, String> descMap = new HashMap<>();
        for (Map<String, Object> e : enriched) {
            if (e.get("name") instanceof String n && e.get("description") instanceof String d)
                descMap.put(n, d);
        }
        for (ApiParameter p : params) {
            String d = descMap.get(p.getName());
            if (d != null && !d.isBlank()) p.setDescription(d);
        }
    }

    private void mergeApiFieldDescriptions(List<ApiField> fields, List<Map<String, Object>> enriched) {
        if (fields == null || enriched == null) return;
        Map<String, String> descMap = new HashMap<>();
        for (Map<String, Object> e : enriched) {
            if (e.get("name") instanceof String n && e.get("description") instanceof String d)
                descMap.put(n, d);
        }
        for (ApiField f : fields) {
            String d = descMap.get(f.getName());
            if (d != null && !d.isBlank()) f.setDescription(d);
        }
    }

    private String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }

    // ── Internal record ────────────────────────────────────────

    private record IndexedApi(int index, ExtractedApi api) {}
}
