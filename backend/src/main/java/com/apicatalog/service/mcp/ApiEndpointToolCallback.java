package com.apicatalog.service.mcp;

import com.apicatalog.model.ApiEndpoint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Encapsulates one ApiEndpoint as an MCP tool definition + HTTP proxy call.
 *
 * NOTE: Spring AI ToolCallback/ToolDefinition interfaces are commented out
 * pending Spring AI MCP dependency resolution for Spring Boot 4.x.
 * The tool name, schema generation, and HTTP proxy logic are complete.
 */
public class ApiEndpointToolCallback {

    private final String       toolName;
    private final ApiEndpoint  endpoint;
    private final String       baseUrl;
    private final ObjectMapper mapper;
    private final HttpClient   http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    public ApiEndpointToolCallback(String toolName, ApiEndpoint endpoint,
                                   String baseUrl, ObjectMapper mapper) {
        this.toolName = toolName;
        this.endpoint = endpoint;
        this.baseUrl  = baseUrl;
        this.mapper   = mapper;
    }

    public String getToolName() { return toolName; }

    public String describe() {
        if (endpoint.getSummary()     != null) return endpoint.getSummary();
        if (endpoint.getDescription() != null) return endpoint.getDescription();
        return endpoint.getMethod() + " " + endpoint.getPath();
    }

    public String call(String toolInput) {
        try {
            JsonNode args = mapper.readTree(toolInput);
            String path = endpoint.getPath();

            if (endpoint.getParameters() != null) {
                for (var p : endpoint.getParameters()) {
                    if ("PATH".equals(p.getLocation()) && args.has(p.getName())) {
                        path = path.replace("{" + p.getName() + "}",
                                args.get(p.getName()).asText());
                    }
                }
            }

            StringBuilder url = new StringBuilder(baseUrl).append(path);
            boolean first = true;
            if (endpoint.getParameters() != null) {
                for (var p : endpoint.getParameters()) {
                    if ("QUERY".equals(p.getLocation()) && args.has(p.getName())) {
                        url.append(first ? "?" : "&")
                           .append(p.getName()).append("=")
                           .append(args.get(p.getName()).asText());
                        first = false;
                    }
                }
            }

            String method = endpoint.getMethod().toUpperCase();
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url.toString()))
                    .timeout(Duration.ofSeconds(30));

            if (args.has("body") && !method.equals("GET") && !method.equals("DELETE")) {
                reqBuilder.header("content-type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(
                                args.get("body").toString()));
            } else {
                reqBuilder.method(method, HttpRequest.BodyPublishers.noBody());
            }

            HttpResponse<String> response = http.send(reqBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());
            return "HTTP " + response.statusCode() + "\n" + response.body();
        } catch (Exception e) {
            return "Error calling " + endpoint.getMethod() + " " + endpoint.getPath()
                    + ": " + e.getMessage();
        }
    }

    public String buildInputSchema() {
        StringBuilder props = new StringBuilder();
        List<String> required = new ArrayList<>();

        if (endpoint.getParameters() != null) {
            for (var p : endpoint.getParameters()) {
                if (!props.isEmpty()) props.append(",");
                props.append("\"").append(p.getName()).append("\":{\"type\":\"string\"");
                if (p.getDescription() != null)
                    props.append(",\"description\":\"").append(escape(p.getDescription())).append("\"");
                props.append("}");
                if (p.isRequired()) required.add(p.getName());
            }
        }

        if (endpoint.getRequestBodyType() != null) {
            if (!props.isEmpty()) props.append(",");
            props.append("\"body\":{\"type\":\"object\",\"description\":\"Request body\"}");
        }

        String req = required.isEmpty() ? ""
                : ",\"required\":[\"" + String.join("\",\"", required) + "\"]";
        return "{\"type\":\"object\",\"properties\":{" + props + "}" + req + "}";
    }

    private String escape(String s) { return s.replace("\"", "\\\""); }
}
