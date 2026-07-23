package com.apicatalog.service.llm;

import com.apicatalog.config.LlmProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Anthropic Messages API client using java.net.http (no extra Maven dependency).
 * Uses Jackson (already on the classpath via Spring Boot) for serialization.
 */
@Component
public class AnthropicClient implements LlmClient {

    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int    MAX_TOKENS         = 4096;

    private final LlmProperties props;
    private final ObjectMapper   mapper;
    private final HttpClient     http;

    public AnthropicClient(LlmProperties props, ObjectMapper mapper) {
        this.props  = props;
        this.mapper = mapper;
        this.http   = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) throws Exception {
        // Build request body
        ObjectNode body = mapper.createObjectNode();
        body.put("model", props.getModel());
        body.put("max_tokens", MAX_TOKENS);
        body.put("system", systemPrompt);

        ArrayNode messages = body.putArray("messages");
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "content");
        userMsg.put("content", userPrompt);

        // Fix: messages role must be "user"
        userMsg.put("role", "user");

        String requestBody = mapper.writeValueAsString(body);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(props.getBaseUrl() + "/v1/messages"))
                .header("x-api-key", props.getApiKey())
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(120))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Anthropic API error " + response.statusCode() + ": " + response.body());
        }

        // Concatenate all text content blocks
        JsonNode root = mapper.readTree(response.body());
        StringBuilder text = new StringBuilder();
        for (JsonNode block : root.path("content")) {
            if ("text".equals(block.path("type").asText())) {
                text.append(block.path("text").asText());
            }
        }
        return text.toString().trim();
    }
}
