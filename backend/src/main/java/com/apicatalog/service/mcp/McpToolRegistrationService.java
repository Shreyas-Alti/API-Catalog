package com.apicatalog.service.mcp;

import com.apicatalog.model.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Stub — MCP tool registration is a no-op until Spring AI MCP server
 * dependency compatibility with Spring Boot 4.x is confirmed.
 * All wiring points (save/rescan/delete/patch/regenerate) are in place;
 * re-enable by replacing this stub with the full implementation once the
 * correct Spring AI artifact version is resolved.
 */
@Service
public class McpToolRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(McpToolRegistrationService.class);

    public void registerForRepository(Repository repo) {
        log.debug("MCP stub: registerForRepository({}) — no-op", repo.getName());
    }

    public void unregisterForRepository(Long repoId) {
        log.debug("MCP stub: unregisterForRepository({}) — no-op", repoId);
    }
}
