package com.apicatalog.service.llm;

/**
 * One interface, one implementation — no multi-provider factory.
 * Extend to additional providers only when actually needed.
 */
public interface LlmClient {
    /**
     * @param systemPrompt the system-level instruction
     * @param userPrompt   the per-request content
     * @return concatenated text of all "text" content blocks in the response
     */
    String complete(String systemPrompt, String userPrompt) throws Exception;
}
