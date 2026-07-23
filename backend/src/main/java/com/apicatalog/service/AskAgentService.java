package com.apicatalog.service;

import com.apicatalog.config.LlmProperties;
import com.apicatalog.model.ApiEndpoint;
import com.apicatalog.model.Repository;
import com.apicatalog.repository.RepositoryRepo;
import com.apicatalog.service.llm.LlmClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.stream.Collectors;

@Service
public class AskAgentService {

    private static final String SYSTEM_PROMPT = """
        You answer questions about a catalog of API endpoints using ONLY the
        endpoint list provided below. Never invent an endpoint that isn't in
        the list. If nothing in the list answers the question, say so plainly
        rather than guessing. When you reference an endpoint, cite its exact
        method and path, e.g. "GET /users/{id}".
        """;

    private final LlmClient      llmClient;
    private final RepositoryRepo repositoryRepo;
    private final LlmProperties  llmProperties;

    public AskAgentService(LlmClient llmClient, RepositoryRepo repositoryRepo,
                           LlmProperties llmProperties) {
        this.llmClient      = llmClient;
        this.repositoryRepo = repositoryRepo;
        this.llmProperties  = llmProperties;
    }

    public String ask(Long repoId, String question) {
        if (!llmProperties.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "LLM features are disabled (llm.enabled=false)");
        }
        if (question == null || question.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question is required");
        }

        Repository repo = repositoryRepo.findByIdWithEndpoints(repoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Repository not found: " + repoId));

        String endpointList = repo.getEndpoints().stream()
                .map(this::formatLine)
                .collect(Collectors.joining("\n"));

        String userPrompt = "Endpoints in \"" + repo.getName() + "\":\n" + endpointList
                + "\n\nQuestion: " + question;

        try {
            return llmClient.complete(SYSTEM_PROMPT, userPrompt).trim();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Ask Agent failed: " + e.getMessage());
        }
    }

    private String formatLine(ApiEndpoint e) {
        StringBuilder sb = new StringBuilder();
        sb.append(e.getMethod()).append(" ").append(e.getPath());
        if (e.getSummary() != null)         sb.append(" — ").append(e.getSummary());
        else if (e.getDescription() != null) sb.append(" — ").append(e.getDescription());
        if (e.getTags() != null && !e.getTags().isEmpty())
            sb.append(" [").append(String.join(", ", e.getTags())).append("]");
        return sb.toString();
    }
}
