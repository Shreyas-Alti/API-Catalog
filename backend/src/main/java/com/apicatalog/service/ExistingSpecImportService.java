package com.apicatalog.service;

import com.apicatalog.model.ApiField;
import com.apicatalog.model.ApiParameter;
import com.apicatalog.model.ExtractedApi;
import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Checks whether the cloned repository contains an existing, committed OpenAPI spec
 * and, if so, imports it directly as {@link ExtractedApi} objects — skipping the
 * static parser and (because every imported endpoint already has a description)
 * effectively skipping LLM enrichment too.
 */
@Service
public class ExistingSpecImportService {

    private static final List<String> SPEC_FILENAMES = List.of(
            "openapi.yaml", "openapi.yml", "openapi.json",
            "swagger.yaml", "swagger.yml", "swagger.json");

    private static final List<String> SPEC_DIRS = List.of(
            "", "docs/", "api/", "spec/", "specs/", "openapi/", ".github/", "src/main/resources/");

    /** Scan conventional locations for a committed OpenAPI / Swagger spec file. */
    public Optional<Path> findExistingSpec(Path repoRoot) {
        for (String dir : SPEC_DIRS) {
            for (String name : SPEC_FILENAMES) {
                Path p = repoRoot.resolve(dir + name);
                if (Files.exists(p)) return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    /**
     * Attempt to locate and parse a committed spec.
     *
     * @return {@code Optional.empty()} when no spec is found or the file is malformed —
     *         the caller falls back to static extraction in that case.
     */
    public Optional<List<ExtractedApi>> tryImport(Path repoRoot) {
        Optional<Path> specPath = findExistingSpec(repoRoot);
        if (specPath.isEmpty()) return Optional.empty();

        ParseOptions options = new ParseOptions();
        options.setResolve(true); // resolve $refs so schema.getProperties() works directly

        SwaggerParseResult result = new OpenAPIParser()
                .readLocation(specPath.get().toUri().toString(), null, options);

        if (result.getOpenAPI() == null) {
            return Optional.empty(); // malformed — let caller fall back to static extraction
        }

        return Optional.of(mapToEndpoints(result.getOpenAPI(),
                specPath.get().getFileName().toString()));
    }

    // ── Mapping ─────────────────────────────────────────────────────────────

    private List<ExtractedApi> mapToEndpoints(OpenAPI openApi, String sourceFileName) {
        List<ExtractedApi> endpoints = new ArrayList<>();
        if (openApi.getPaths() == null) return endpoints;

        openApi.getPaths().forEach((path, pathItem) ->
                pathItem.readOperationsMap().forEach((httpMethod, operation) -> {
                    ExtractedApi api = new ExtractedApi();
                    api.setMethod(httpMethod.name());
                    api.setPath(path);
                    api.setSummary(operation.getSummary());
                    api.setDescription(operation.getDescription());
                    api.setTags(operation.getTags() != null ? operation.getTags() : List.of());
                    api.setSourceFile(sourceFileName);
                    api.setAiGenerated(false);  // human-authored spec
                    api.setNeedsReview(false);  // trusted, but still goes through Review once
                    api.setManuallyEdited(false);

                    api.setParameters(mapParameters(operation));
                    mapRequestBody(operation, api);
                    mapResponses(operation, api);

                    endpoints.add(api);
                })
        );
        return endpoints;
    }

    private List<ApiParameter> mapParameters(Operation operation) {
        if (operation.getParameters() == null) return List.of();
        List<ApiParameter> params = new ArrayList<>();
        for (var p : operation.getParameters()) {
            ApiParameter ap = new ApiParameter();
            ap.setName(p.getName());
            ap.setLocation(p.getIn() != null ? p.getIn().toUpperCase() : "QUERY");
            ap.setRequired(Boolean.TRUE.equals(p.getRequired()));
            ap.setDescription(p.getDescription());
            if (p.getSchema() != null) ap.setType(p.getSchema().getType());
            params.add(ap);
        }
        return params;
    }

    private void mapRequestBody(Operation operation, ExtractedApi api) {
        RequestBody body = operation.getRequestBody();
        if (body == null || body.getContent() == null) return;

        MediaType mt = firstJsonMediaType(body.getContent());
        if (mt == null || mt.getSchema() == null) return;

        Schema<?> schema = mt.getSchema();
        api.setRequestBodyType(schemaTypeName(schema));
        api.setRequestBodyFields(schemaToFields(schema));
    }

    private void mapResponses(Operation operation, ExtractedApi api) {
        ApiResponses responses = operation.getResponses();
        if (responses == null) return;

        List<Integer> codes = new ArrayList<>();
        for (String code : responses.keySet()) {
            if (code.matches("\\d+")) {
                try { codes.add(Integer.parseInt(code)); } catch (NumberFormatException ignored) {}
            }
        }
        api.setStatusCodes(codes);

        // First 2xx response schema → response body shape
        responses.entrySet().stream()
                .filter(e -> e.getKey().startsWith("2"))
                .findFirst()
                .map(Map.Entry::getValue)
                .map(ApiResponse::getContent)
                .map(this::firstJsonMediaType)
                .filter(mt -> mt != null && mt.getSchema() != null)
                .ifPresent(mt -> {
                    api.setResponseBodyType(schemaTypeName(mt.getSchema()));
                    api.setResponseBodyFields(schemaToFields(mt.getSchema()));
                });
    }

    private MediaType firstJsonMediaType(Content content) {
        if (content == null) return null;
        MediaType json = content.get("application/json");
        return json != null ? json : content.values().stream().findFirst().orElse(null);
    }

    private String schemaTypeName(Schema<?> schema) {
        if (schema.getName() != null && !schema.getName().isBlank()) return schema.getName();
        if (schema.get$ref() != null) {
            String ref = schema.get$ref();
            return ref.substring(ref.lastIndexOf('/') + 1);
        }
        return schema.getType() != null ? schema.getType() : "object";
    }

    @SuppressWarnings("unchecked")
    private List<ApiField> schemaToFields(Schema<?> schema) {
        if (schema.getProperties() == null) return List.of();
        List<ApiField> fields = new ArrayList<>();
        schema.getProperties().forEach((rawName, propSchema) -> {
            ApiField f = new ApiField();
            f.setName(String.valueOf(rawName));
            Schema<?> ps = (Schema<?>) propSchema;
            f.setType(ps.getType() != null ? ps.getType() : schemaTypeName(ps));
            f.setDescription(ps.getDescription());
            fields.add(f);
        });
        return fields;
    }
}
