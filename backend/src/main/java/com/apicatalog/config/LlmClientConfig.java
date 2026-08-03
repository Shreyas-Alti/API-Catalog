package com.apicatalog.config;

import com.apicatalog.service.llm.AnthropicClient;
import com.apicatalog.service.llm.AzureOpenAiClient;
import com.apicatalog.service.llm.GeminiClient;
import com.apicatalog.service.llm.LlmClient;
import com.apicatalog.service.llm.OpenAiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the correct LlmClient implementation based on llm.provider.
 *
 * Supported values:
 *   llm.provider: anthropic   → AnthropicClient  (default)
 *   llm.provider: gemini      → GeminiClient
 */
@Configuration
public class LlmClientConfig {

    @Bean
    public LlmClient llmClient(LlmProperties props, ObjectMapper mapper) {
        return switch (props.getProvider().toLowerCase()) {
            case "openai"       -> new OpenAiClient(props, mapper);
            case "gemini"       -> new GeminiClient(props, mapper);
            case "azure-openai",
                 "azure"        -> new AzureOpenAiClient(props, mapper);
            default             -> new AnthropicClient(props, mapper);
        };
    }
}
