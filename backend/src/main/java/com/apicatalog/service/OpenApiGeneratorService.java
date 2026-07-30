package com.apicatalog.service;

import com.apicatalog.model.ApiEndpoint;
import com.apicatalog.model.ApiField;
import com.apicatalog.model.ApiParameter;
import com.apicatalog.model.Repository;
import com.apicatalog.repository.RepositoryRepo;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        // AI-generated intro description
        if (repo.getApiDescription() != null && !repo.getApiDescription().isBlank()) {
            info.put("description", repo.getApiDescription());
        }
        doc.put("info", info);

        // Top-level tags with AI-generated descriptions (drives Scalar's sidebar sections)
        List<Map<String, Object>> tagList = buildTagList(repo);
        if (!tagList.isEmpty()) doc.put("tags", tagList);

        if (repo.getHostUrl() != null && !repo.getHostUrl().isBlank()) {
            List<Map<String, Object>> servers = new ArrayList<>();
            Map<String, Object> server = new LinkedHashMap<>();
            server.put("url", repo.getHostUrl());
            server.put("description", "API Base URL");
            servers.add(server);
            doc.put("servers", servers);
        }

        // Collect distinct complex (non-primitive) schema types, with sanitised names
        Map<String, String> rawToSanitized = new LinkedHashMap<>(); // rawTypeName → sanitizedKey
        for (ApiEndpoint ep : repo.getEndpoints()) {
            for (String raw : new String[]{ ep.getRequestBodyType(), ep.getResponseBodyType() }) {
                if (raw != null && !rawToSanitized.containsKey(raw) && !isPrimitive(raw)) {
                    rawToSanitized.put(raw, sanitizeSchemaName(raw));
                }
            }
        }
        // Also register complex types found in field definitions (nested DTOs, e.g. author: Profile)
        for (ApiEndpoint ep : repo.getEndpoints()) {
            collectFieldTypes(ep.getRequestBodyFields(),  rawToSanitized);
            collectFieldTypes(ep.getResponseBodyFields(), rawToSanitized);
        }
        Set<String> schemaNames = new LinkedHashSet<>(rawToSanitized.values());
        Map<String, ApiEndpoint> schemaSourceEndpoint = new LinkedHashMap<>();
        for (ApiEndpoint ep : repo.getEndpoints()) {
            if (ep.getRequestBodyType()  != null && rawToSanitized.containsKey(ep.getRequestBodyType()))
                schemaSourceEndpoint.putIfAbsent(rawToSanitized.get(ep.getRequestBodyType()), ep);
            if (ep.getResponseBodyType() != null && rawToSanitized.containsKey(ep.getResponseBodyType()))
                schemaSourceEndpoint.putIfAbsent(rawToSanitized.get(ep.getResponseBodyType()), ep);
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

    /** Build the top-level tags array with AI-generated descriptions. */
    private List<Map<String, Object>> buildTagList(Repository repo) {
        // Collect ordered unique tags from endpoints
        Set<String> orderedTags = new LinkedHashSet<>();
        for (ApiEndpoint ep : repo.getEndpoints()) {
            if (ep.getTags() != null) orderedTags.addAll(ep.getTags());
        }
        if (orderedTags.isEmpty()) return List.of();

        // Parse stored tag descriptions JSON (may be null)
        Map<String, String> tagDescs = new LinkedHashMap<>();
        if (repo.getTagDescriptionsJson() != null) {
            try {
                JsonNode node = mapper.readTree(repo.getTagDescriptionsJson());
                node.fields().forEachRemaining(e -> tagDescs.put(e.getKey(), e.getValue().asText()));
            } catch (Exception ignored) {}
        }

        List<Map<String, Object>> list = new ArrayList<>();
        for (String tag : orderedTags) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("name", tag);
            String desc = tagDescs.get(tag);
            if (desc != null && !desc.isBlank()) t.put("description", desc);
            list.add(t);
        }
        return list;
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
        // Auto-inject path parameters present in the URL template but not in the params list
        if (ep.getPath() != null) {
            Set<String> declaredPathParams = new HashSet<>();
            for (Map<String, Object> p : params) {
                if ("path".equals(p.get("in"))) declaredPathParams.add((String) p.get("name"));
            }
            List<Map<String, Object>> injected = new ArrayList<>();
            Matcher pathVarM = Pattern.compile("\\{([^}]+)\\}").matcher(ep.getPath());
            while (pathVarM.find()) {
                String pName = pathVarM.group(1);
                if (declaredPathParams.add(pName)) {
                    Map<String, Object> pathParam = new LinkedHashMap<>();
                    pathParam.put("name", pName);
                    pathParam.put("in", "path");
                    pathParam.put("required", true);
                    pathParam.put("schema", Map.of("type", "string"));
                    injected.add(pathParam);
                }
            }
            params.addAll(0, injected);
        }
        if (!params.isEmpty()) op.put("parameters", params);

        // Request body — use sanitized $ref for complex types, inline schema for primitives
        if (ep.getRequestBodyType() != null) {
            Map<String, Object> schema;
            if (isPrimitive(ep.getRequestBodyType())) {
                schema = typeToSchema(ep.getRequestBodyType());
            } else {
                schema = Map.of("$ref", "#/components/schemas/" + sanitizeSchemaName(ep.getRequestBodyType()));
            }
            Map<String, Object> content = new LinkedHashMap<>();
            Map<String, Object> mediaType = new LinkedHashMap<>();
            mediaType.put("schema", schema);
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
            // Include response body whenever we have either a known type OR an LLM-generated example
            boolean hasType    = code >= 200 && code < 300 && ep.getResponseBodyType() != null;
            boolean hasExample = code >= 200 && code < 300 && ep.getResponseExample() != null;
            if (hasType || hasExample) {
                Map<String, Object> content = new LinkedHashMap<>();
                Map<String, Object> mediaType = new LinkedHashMap<>();
                Map<String, Object> schema;
                if (hasType) {
                    schema = isPrimitive(ep.getResponseBodyType())
                            ? typeToSchema(ep.getResponseBodyType())
                            : Map.of("$ref", "#/components/schemas/" + sanitizeSchemaName(ep.getResponseBodyType()));
                } else {
                    // No explicit type but we have an example — use generic object schema
                    schema = Map.of("type", "object");
                }
                mediaType.put("schema", schema);
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

    /** Map any parser-extracted type string to an inline OpenAPI schema or a $ref. */
    private Map<String, Object> typeToSchema(String type) {
        if (type == null) return Map.of("type", "string");
        // Normalise: strip leading/trailing whitespace, collapse spaces
        type = type.trim();

        return switch (type) {
            // ── Java ───────────────────────────────────────────
            case "String", "string"                  -> Map.of("type", "string");
            case "Integer", "int"                    -> Map.of("type", "integer", "format", "int32");
            case "Long", "long"                      -> Map.of("type", "integer", "format", "int64");
            case "Boolean", "boolean"                -> Map.of("type", "boolean");
            case "Double", "double",
                 "Float", "number"                  -> Map.of("type", "number");
            case "BigDecimal"                        -> Map.of("type", "number");
            case "UUID"                              -> Map.of("type", "string", "format", "uuid");
            case "Instant", "LocalDateTime",
                 "ZonedDateTime", "OffsetDateTime",
                 "datetime"                          -> Map.of("type", "string", "format", "date-time");
            case "LocalDate", "date"                 -> Map.of("type", "string", "format", "date");
            case "void", "Void", "null", "None"      -> Map.of("type", "null");
            // ── Python ─────────────────────────────────────────
            case "str"                               -> Map.of("type", "string");
            case "bool"                              -> Map.of("type", "boolean");
            case "float"                             -> Map.of("type", "number");
            case "bytes"                             -> Map.of("type", "string", "format", "binary");
            case "Any", "any", "object",
                 "dict", "Dict"                     -> Map.of("type", "object");
            // ── Pydantic / FastAPI special types ────────────────
            case "EmailStr"                          -> Map.of("type", "string", "format", "email");
            case "HttpUrl", "AnyUrl", "Url"          -> Map.of("type", "string", "format", "uri");
            case "condecimal", "Decimal"             -> Map.of("type", "number");
            // ── TypeScript ─────────────────────────────────────
            case "unknown", "never"                  -> Map.of("type", "object");
            // ── Go ─────────────────────────────────────────────
            case "int8", "int16", "int32", "uint",
                 "uint8", "uint16", "uint32",
                 "uint64", "int64"                   -> Map.of("type", "integer");
            case "float32", "float64"                -> Map.of("type", "number");
            case "error"                             -> Map.of("type", "string");
            default -> {
                // ── Python generics: list[X], Optional[X], dict[K,V] ──
                if (type.startsWith("list[") || type.startsWith("List[") ||
                    type.startsWith("set[")  || type.startsWith("Set[")) {
                    // Safe extraction: no regex, no catastrophic backtracking
                    String inner = extractBracketInner(type);
                    yield inner == null ? Map.of("type", "array")
                            : Map.of("type", "array", "items", typeToSchema(inner));
                }
                if (type.startsWith("Optional[")) {
                    String inner = extractBracketInner(type);
                    yield inner == null ? Map.of("type", "object") : typeToSchema(inner);
                }
                if (type.startsWith("dict[") || type.startsWith("Dict[")) {
                    yield Map.of("type", "object");
                }
                if (type.startsWith("Union[")) {
                    yield Map.of("type", "object");
                }
                // ── Java generics: List<X>, Set<X>, etc. ──────────────
                if (type.startsWith("List<") || type.startsWith("Set<") ||
                    type.startsWith("Collection<") || type.startsWith("Iterable<")) {
                    String inner = extractAngleInner(type);
                    yield inner == null ? Map.of("type", "array")
                            : Map.of("type", "array", "items", typeToSchema(inner));
                }
                if (type.startsWith("Map<") || type.startsWith("HashMap<")) {
                    yield Map.of("type", "object");
                }
                // ── Union / nullable suffixes ──────────────────────────
                if (type.contains("|") || type.contains("?")) {
                    String first = type.split("[|?]")[0].trim();
                    yield typeToSchema(first);
                }
                // ── Types with brackets/generics not caught above ──────
                if (type.contains("[") || type.contains("<") || type.contains(" ")) {
                    yield Map.of("type", "object");
                }
                // ── Types with invalid OpenAPI chars ───────────────────
                if (!type.matches("[a-zA-Z0-9._\\-]+")) {
                    yield Map.of("type", "object");
                }
                // ── Assume custom DTO → $ref with sanitised name ───────
                yield Map.of("$ref", "#/components/schemas/" + type);
            }
        };
    }

    /** Safely extract the inner type from bracket generics: list[T] → T. */
    private String extractBracketInner(String type) {
        int open = type.indexOf('[');
        if (open < 0) return null;
        int close = type.lastIndexOf(']');
        if (close <= open) return null;
        return type.substring(open + 1, close).trim();
    }

    /** Safely extract the inner type from angle generics: List<T> → T. */
    private String extractAngleInner(String type) {
        int open = type.indexOf('<');
        if (open < 0) return null;
        int close = type.lastIndexOf('>');
        if (close <= open) return null;
        return type.substring(open + 1, close).trim();
    }

    /**
     * True when typeToSchema() produces an inline primitive schema (no $ref).
     * Used to skip adding primitive types as named components.
     */
    private boolean isPrimitive(String type) {
        if (type == null) return true;
        Map<String, Object> schema = typeToSchema(type);
        return !schema.containsKey("$ref");
    }

    /**
     * Sanitize a raw parser type name into a valid OpenAPI component key
     * (must match {@code ^[a-zA-Z0-9\.\-_]+$}).
     */
    private String sanitizeSchemaName(String name) {
        if (name == null || name.isBlank()) return "Schema";
        // Remove generic wrappers: List<Foo> → Foo, list[Foo] → Foo
        name = name.replaceAll("^(?:List|list|Set|set|Optional|Collection|Iterable)<(.+)>$", "$1");
        name = name.replaceAll("^(?:list|set|dict)\\[(.+)\\]$", "$1");
        // Strip invalid chars (brackets, pipes, spaces, commas, etc.)
        name = name.replaceAll("[^a-zA-Z0-9._\\-]", "_");
        // Collapse multiple underscores / trim leading+trailing
        name = name.replaceAll("_+", "_").replaceAll("^_+|_+$", "");
        return name.isEmpty() ? "Schema" : name;
    }

    /**
     * Collects complex field types into rawToSanitized so nested DTOs get schema stubs.
     */
    private void collectFieldTypes(List<ApiField> fields, Map<String, String> rawToSanitized) {
        if (fields == null) return;
        for (ApiField f : fields) {
            String base = extractBaseFieldType(f.getType());
            if (base != null && !rawToSanitized.containsKey(base) && !isPrimitive(base)) {
                rawToSanitized.put(base, sanitizeSchemaName(base));
            }
        }
    }

    /** Strip Optional[T], List[T], list[T] etc. to get the bare type name. */
    private String extractBaseFieldType(String type) {
        if (type == null) return null;
        type = type.trim();
        for (String prefix : List.of("Optional[", "List[", "Set[", "list[", "set[")) {
            if (type.startsWith(prefix)) {
                int open = type.indexOf('['), close = type.lastIndexOf(']');
                if (open >= 0 && close > open) return type.substring(open + 1, close).trim();
            }
        }
        // Only accept plain identifiers (no spaces, brackets, generics)
        return type.matches("[a-zA-Z][a-zA-Z0-9_]*") ? type : null;
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
