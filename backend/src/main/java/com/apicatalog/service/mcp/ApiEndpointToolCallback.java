package com.apicatalog.service.mcp;

import com.apicatalog.model.ApiEndpoint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Wraps one ApiEndpoint as a Spring AI ToolCallback.
 * When invoked, performs the actual HTTP call against the repository's hostUrl.
 */
public class ApiEndpointToolCallback implements ToolCallback {

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

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(toolName)
                .description(describe())
                .inputSchema(buildInputSchema())
                .build();
    }

    @Override
    public String call(String toolInput) {
        try {
            JsonNode args = mapper.readTree(toolInput);
            String path = endpoint.getPath();

            // Substitute PATH parameters
            if (endpoint.getParameters() != null) {
                for (var p : endpoint.getParameters()) {
                    if ("PATH".equals(p.getLocation()) && args.has(p.getName())) {
                        path = path.replace("{" + p.getName() + "}",
                                args.get(p.getName()).asText());
                    }
                }
            }

            // Build URL with QUERY parameters
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

    private String describe() {
        if (endpoint.getSummary()     != null) return endpoint.getSummary();
        if (endpoint.getDescription() != null) return endpoint.getDescription();
        return endpoint.getMethod() + " " + endpoint.getPath();
    }

    private String buildInputSchema() {
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
