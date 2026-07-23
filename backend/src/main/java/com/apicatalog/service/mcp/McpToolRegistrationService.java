package com.apicatalog.service.mcp;

import com.apicatalog.model.ApiEndpoint;
import com.apicatalog.model.Repository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpSyncServer;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registers/replaces the full set of MCP tools for a repository.
 *
 * Strategy: remove-and-re-add the whole set on any change (per spec §B.4).
 * Tools live in memory only; rebuilt on startup via McpStartupConfig.
 */
@Service
public class McpToolRegistrationService {

    private final McpSyncServer               mcpSyncServer;
    private final ObjectMapper                mapper;
    private final Map<Long, List<String>>     registeredByRepo = new ConcurrentHashMap<>();

    public McpToolRegistrationService(McpSyncServer mcpSyncServer, ObjectMapper mapper) {
        this.mcpSyncServer = mcpSyncServer;
        this.mapper        = mapper;
    }

    public void registerForRepository(Repository repo) {
        unregisterForRepository(repo.getId());

        if (repo.getHostUrl() == null || repo.getHostUrl().isBlank()) return;

        List<String>       names     = new ArrayList<>();
        List<ToolCallback> callbacks = new ArrayList<>();

        for (ApiEndpoint ep : repo.getEndpoints()) {
            String name = toolName(repo, ep);
            names.add(name);
            callbacks.add(new ApiEndpointToolCallback(name, ep, repo.getHostUrl(), mapper));
        }

        McpToolUtils.toSyncToolSpecifications(callbacks.toArray(new ToolCallback[0]))
                .forEach(mcpSyncServer::addTool);

        registeredByRepo.put(repo.getId(), names);
        mcpSyncServer.notifyToolsListChanged();
    }

    public void unregisterForRepository(Long repoId) {
        List<String> existing = registeredByRepo.remove(repoId);
        if (existing != null) {
            existing.forEach(mcpSyncServer::removeTool);
            mcpSyncServer.notifyToolsListChanged();
        }
    }

    private String toolName(Repository repo, ApiEndpoint ep) {
        return (repo.getName() + "_" + ep.getMethod() + "_" + ep.getPath())
                .toLowerCase()
                .replaceAll("[{}]", "")
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_|_$", "");
    }
}
