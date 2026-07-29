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
 * Azure OpenAI chat completions client.
 *
 * Endpoint: POST {baseUrl}/openai/deployments/{model}/chat/completions?api-version={apiVersion}
 * Auth header: api-key: {apiKey}
 * Compatible with gpt-4o, gpt-4o-mini, gpt-4-turbo, gpt-35-turbo, etc.
 *
 * Required config:
 *   llm.provider:  azure-openai
 *   llm.api-key:   <your Azure OpenAI key>
 *   llm.model:     <deployment name, e.g. gpt-4o>
 *   llm.base-url:  https://<resource-name>.openai.azure.com
 */
public class AzureOpenAiClient implements LlmClient {

    private static final int MAX_TOKENS = 4096;

    private final LlmProperties props;
    private final ObjectMapper   mapper;
    private final HttpClient     http;

    public AzureOpenAiClient(LlmProperties props, ObjectMapper mapper) {
        this.props  = props;
        this.mapper = mapper;
        this.http   = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) throws Exception {
        // Build request body (OpenAI chat completions format)
        ObjectNode body = mapper.createObjectNode();

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

        // URL: {baseUrl}/openai/deployments/{deploymentName}/chat/completions?api-version=...
        String apiVersion = props.getApiVersion() != null && !props.getApiVersion().isBlank()
                ? props.getApiVersion() : "2024-02-15-preview";
        String base = props.getBaseUrl().replaceAll("/$", "");
        String url  = base
                + "/openai/deployments/" + props.getModel()
                + "/chat/completions?api-version=" + apiVersion;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("api-key", props.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(120))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Azure OpenAI error " + response.statusCode() + ": " + response.body());
        }

        // Extract text from choices[0].message.content
        JsonNode root = mapper.readTree(response.body());
        String content = root.path("choices").path(0).path("message").path("content").asText(null);
        return content != null ? content.trim() : "";
    }
}
