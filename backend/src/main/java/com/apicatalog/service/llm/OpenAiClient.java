package com.apicatalog.service.llm;

import com.apicatalog.config.LlmProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Standard OpenAI chat completions client.
 *
 * Endpoint: POST https://api.openai.com/v1/chat/completions
 * Auth header: Authorization: Bearer {apiKey}
 *
 * Required config:
 *   llm.provider:  openai
 *   llm.api-key:   sk-...
 *   llm.model:     gpt-4o (or gpt-4o-mini, gpt-4-turbo, etc.)
 *   llm.base-url:  https://api.openai.com  (optional, defaults to this)
 */
public class OpenAiClient implements LlmClient {

    private static final int MAX_TOKENS = 4096;

    private final LlmProperties props;
    private final ObjectMapper   mapper;
    private final HttpClient     http;

    public OpenAiClient(LlmProperties props, ObjectMapper mapper) {
        this.props  = props;
        this.mapper = mapper;
        this.http   = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", props.getModel());

        ArrayNode messages = body.putArray("messages");
        messages.addObject()
                .put("role",    "system")
                .put("content", systemPrompt);
        messages.addObject()
                .put("role",    "user")
                .put("content", userPrompt);

        body.put("temperature", 0.2);
        body.put("max_tokens",  MAX_TOKENS);

        String requestBody = mapper.writeValueAsString(body);

        String base = props.getBaseUrl() != null && !props.getBaseUrl().isBlank()
                ? props.getBaseUrl().replaceAll("/$", "")
                : "https://api.openai.com";
        String url = base + "/v1/chat/completions";

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(120));

        // Azure AI Foundry uses api-key header; standard OpenAI uses Bearer token
        if (base.contains(".azure.com")) {
            reqBuilder.header("api-key", props.getApiKey());
        } else {
            reqBuilder.header("Authorization", "Bearer " + props.getApiKey());
        }

        HttpRequest request = reqBuilder.build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("OpenAI error " + response.statusCode() + ": " + response.body());
        }

        JsonNode root = mapper.readTree(response.body());
        String content = root.path("choices").path(0).path("message").path("content").asText(null);
        return content != null ? content.trim() : "";
    }
}
