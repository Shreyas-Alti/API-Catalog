package com.apicatalog.service;

import com.apicatalog.model.ApiEndpoint;
import com.apicatalog.model.ApiField;
import com.apicatalog.model.ApiParameter;
import com.apicatalog.model.Repository;
import com.apicatalog.repository.RepositoryRepo;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Generates an OpenAPI 3.1.0 document from the persisted ApiEndpoint rows,
 * caches it on the Repository entity, and validates it with swagger-parser.
 *
 * GET /api/repositories/{id}/openapi.json returns the cache (regenerating if dirty).
 */
@Service
public class OpenApiGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(OpenApiGeneratorService.class);

    private final RepositoryRepo repositoryRepo;
    private final ObjectMapper   mapper;

    public OpenApiGeneratorService(RepositoryRepo repositoryRepo, ObjectMapper mapper) {
        this.repositoryRepo = repositoryRepo;
        this.mapper         = mapper;
    }

    public String getOrGenerate(Long repoId) {
        Repository repo = repositoryRepo.findByIdWithEndpoints(repoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Repository not found: " + repoId));

        if (!repo.isOpenapiDirty() && repo.getOpenapiCache() != null) {
            return repo.getOpenapiCache();
        }
        return generate(repo);
    }

    private String generate(Repository repo) {
        try {
            Map<String, Object> doc = buildDocument(repo);
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(doc);

            // Validate — log warnings but always serve the document
            SwaggerParseResult result = new OpenAPIV3Parser().readContents(json);
            if (result.getMessages() != null && !result.getMessages().isEmpty()) {
                log.warn("Generated OpenAPI for '{}' has validation issues: {}",
                        repo.getName(), result.getMessages());
            }

            repo.setOpenapiCache(json);
            repo.setOpenapiDirty(false);
            repo.setOpenapiGeneratedAt(LocalDateTime.now());
            repositoryRepo.save(repo);
            return json;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to generate OpenAPI document: " + e.getMessage());
        }
    }

    // ── Document builder ───────────────────────────────────────

    private Map<String, Object> buildDocument(Repository repo) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("openapi", "3.1.0");

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("title", repo.getName());
        info.put("version", "1.0.0");
        doc.put("info", info);

        if (repo.getHostUrl() != null && !repo.getHostUrl().isBlank()) {
            List<Map<String, Object>> servers = new ArrayList<>();
            Map<String, Object> server = new LinkedHashMap<>();
            server.put("url", repo.getHostUrl());
            server.put("description", "API Base URL");
            servers.add(server);
            doc.put("servers", servers);
        }

        // Collect distinct schema types
        Set<String> schemaNames = new LinkedHashSet<>();
        Map<String, ApiEndpoint> schemaSourceEndpoint = new LinkedHashMap<>();
        for (ApiEndpoint ep : repo.getEndpoints()) {
            if (ep.getRequestBodyType()  != null) {
                schemaNames.add(ep.getRequestBodyType());
                schemaSourceEndpoint.putIfAbsent(ep.getRequestBodyType(), ep);
            }
            if (ep.getResponseBodyType() != null) {
                schemaNames.add(ep.getResponseBodyType());
                schemaSourceEndpoint.putIfAbsent(ep.getResponseBodyType(), ep);
            }
        }

        // Build paths
        Map<String, Map<String, Object>> paths = new LinkedHashMap<>();
        for (ApiEndpoint ep : repo.getEndpoints()) {
            String path = ep.getPath();
            paths.computeIfAbsent(path, k -> new LinkedHashMap<>())
                    .put(ep.getMethod().toLowerCase(), buildOperation(ep));
        }
        doc.put("paths", paths);

        // Build components.schemas
        if (!schemaNames.isEmpty()) {
            Map<String, Object> schemas = new LinkedHashMap<>();
            for (String name : schemaNames) {
                ApiEndpoint source = schemaSourceEndpoint.get(name);
                schemas.put(name, buildSchema(name, source));
            }
            Map<String, Object> components = new LinkedHashMap<>();
            components.put("schemas", schemas);
            doc.put("components", components);
        }

        return doc;
    }

    private Map<String, Object> buildOperation(ApiEndpoint ep) {
        Map<String, Object> op = new LinkedHashMap<>();

        if (ep.getSummary() != null)     op.put("summary",     ep.getSummary());
        if (ep.getDescription() != null) op.put("description", ep.getDescription());
        if (ep.getTags() != null && !ep.getTags().isEmpty()) op.put("tags", ep.getTags());

        String handlerId = ep.getController() != null
                ? ep.getController() + "_" + ep.getHandler()
                : ep.getHandler();
        if (handlerId != null) op.put("operationId", handlerId);

        // Parameters (non-BODY)
        List<Map<String, Object>> params = new ArrayList<>();
        if (ep.getParameters() != null) {
            for (ApiParameter p : ep.getParameters()) {
                if ("BODY".equals(p.getLocation())) continue;
                Map<String, Object> param = new LinkedHashMap<>();
                param.put("name", p.getName());
                param.put("in", p.getLocation().toLowerCase());
                param.put("required", p.isRequired());
                if (p.getDescription() != null) param.put("description", p.getDescription());
                param.put("schema", typeToSchema(p.getType()));
                params.add(param);
            }
        }
        if (!params.isEmpty()) op.put("parameters", params);

        // Request body
        if (ep.getRequestBodyType() != null) {
            Map<String, Object> content = new LinkedHashMap<>();
            Map<String, Object> mediaType = new LinkedHashMap<>();
            mediaType.put("schema", Map.of("$ref", "#/components/schemas/" + ep.getRequestBodyType()));
            if (ep.getRequestExample() != null) mediaType.put("example", ep.getRequestExample());
            content.put("application/json", mediaType);
            op.put("requestBody", Map.of("required", true, "content", content));
        }

        // Responses
        Map<String, Object> responses = new LinkedHashMap<>();
        List<Integer> codes = ep.getStatusCodes();
        if (codes == null || codes.isEmpty()) codes = List.of(200);
        for (Integer code : codes) {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("description", statusDescription(code));
            if (code >= 200 && code < 300 && ep.getResponseBodyType() != null) {
                Map<String, Object> content = new LinkedHashMap<>();
                Map<String, Object> mediaType = new LinkedHashMap<>();
                mediaType.put("schema", Map.of("$ref", "#/components/schemas/" + ep.getResponseBodyType()));
                if (ep.getResponseExample() != null) mediaType.put("example", ep.getResponseExample());
                content.put("application/json", mediaType);
                resp.put("content", content);
            }
            responses.put(String.valueOf(code), resp);
        }
        op.put("responses", responses);

        // x- vendor extensions for the frontend
        if (ep.getSourceFile() != null) op.put("x-source-file", ep.getSourceFile());
        if (ep.getSourceLine() != null) op.put("x-source-line", ep.getSourceLine());
        op.put("x-ai-generated", ep.isAiGenerated());
        op.put("x-needs-review", ep.isNeedsReview());

        return op;
    }

    private Map<String, Object> buildSchema(String name, ApiEndpoint source) {
        List<ApiField> fields = null;
        if (source != null) {
            if (name.equals(source.getRequestBodyType()))  fields = source.getRequestBodyFields();
            if (name.equals(source.getResponseBodyType())) fields = source.getResponseBodyFields();
        }

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        if (fields == null || fields.isEmpty()) return schema;

        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (ApiField f : fields) {
            Map<String, Object> prop = new LinkedHashMap<>(typeToSchema(f.getType()));
            if (f.getDescription() != null) prop.put("description", f.getDescription());
            properties.put(f.getName(), prop);
            if (f.getValidations() != null &&
                f.getValidations().stream().anyMatch(v -> v.contains("NotNull") || v.contains("NotBlank"))) {
                required.add(f.getName());
            }
        }
        schema.put("properties", properties);
        if (!required.isEmpty()) schema.put("required", required);
        return schema;
    }

    // ── Type mapping ───────────────────────────────────────────

    private Map<String, Object> typeToSchema(String javaType) {
        if (javaType == null) return Map.of("type", "string");
        return switch (javaType) {
            case "String"                    -> Map.of("type", "string");
            case "Integer", "int"            -> Map.of("type", "integer", "format", "int32");
            case "Long", "long"              -> Map.of("type", "integer", "format", "int64");
            case "Boolean", "boolean"        -> Map.of("type", "boolean");
            case "Double", "double",
                 "Float", "float"            -> Map.of("type", "number");
            case "BigDecimal"                -> Map.of("type", "number");
            case "UUID"                      -> Map.of("type", "string", "format", "uuid");
            case "Instant", "LocalDateTime",
                 "ZonedDateTime",
                 "OffsetDateTime"            -> Map.of("type", "string", "format", "date-time");
            case "LocalDate"                 -> Map.of("type", "string", "format", "date");
            default -> {
                // Generic: List<X>, Set<X>
                if (javaType.startsWith("List<") || javaType.startsWith("Set<") ||
                    javaType.startsWith("Collection<")) {
                    String inner = javaType.replaceAll("^\\w+<(.+)>$", "$1").trim();
                    yield Map.of("type", "array", "items", typeToSchema(inner));
                }
                // Map<K,V>
                if (javaType.startsWith("Map<")) {
                    yield Map.of("type", "object");
                }
                // Custom DTO → $ref
                yield Map.of("$ref", "#/components/schemas/" + javaType);
            }
        };
    }

    private String statusDescription(int code) {
        return switch (code) {
            case 200 -> "OK";          case 201 -> "Created";
            case 204 -> "No Content";  case 400 -> "Bad Request";
            case 401 -> "Unauthorized";case 403 -> "Forbidden";
            case 404 -> "Not Found";   case 409 -> "Conflict";
            case 422 -> "Unprocessable Entity";
            case 500 -> "Internal Server Error";
            default  -> "Response";
        };
    }
}
