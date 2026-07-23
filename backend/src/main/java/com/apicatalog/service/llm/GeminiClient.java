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
 * Google Gemini generateContent API client.
 *
 * Endpoint: POST {baseUrl}/v1beta/models/{model}:generateContent?key={apiKey}
 * No extra Maven dependency — uses java.net.http + Jackson.
 */
public class GeminiClient implements LlmClient {

    private static final int MAX_OUTPUT_TOKENS = 4096;

    private final LlmProperties props;
    private final ObjectMapper   mapper;
    private final HttpClient     http;

    public GeminiClient(LlmProperties props, ObjectMapper mapper) {
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

        // System instruction
        ObjectNode sysInstruction = body.putObject("system_instruction");
        ArrayNode  sysParts       = sysInstruction.putArray("parts");
        sysParts.addObject().put("text", systemPrompt);

        // User turn
        ArrayNode  contents  = body.putArray("contents");
        ObjectNode userTurn  = contents.addObject();
        userTurn.put("role", "user");
        ArrayNode userParts = userTurn.putArray("parts");
        userParts.addObject().put("text", userPrompt);

        // Generation config
        ObjectNode genConfig = body.putObject("generationConfig");
        genConfig.put("temperature", 0.2);
        genConfig.put("maxOutputTokens", MAX_OUTPUT_TOKENS);

        String requestBody = mapper.writeValueAsString(body);

        // URL: {baseUrl}/v1beta/models/{model}:generateContent?key={apiKey}
        String url = props.getBaseUrl()
                + "/v1beta/models/" + props.getModel()
                + ":generateContent?key=" + props.getApiKey();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(120))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Gemini API error " + response.statusCode() + ": " + response.body());
        }

        // Extract text from candidates[0].content.parts[0].text
        JsonNode root = mapper.readTree(response.body());
        StringBuilder text = new StringBuilder();
        for (JsonNode part : root.path("candidates").path(0).path("content").path("parts")) {
            String t = part.path("text").asText(null);
            if (t != null) text.append(t);
        }
        return text.toString().trim();
    }
}
