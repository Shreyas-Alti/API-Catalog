package com.apicatalog.config;

import com.apicatalog.repository.RepositoryRepo;
import com.apicatalog.service.mcp.McpToolRegistrationService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Calls McpToolRegistrationService on startup.
 * Currently a no-op (stub) — full MCP registration will activate once
 * Spring AI MCP server dependency is resolved for Spring Boot 4.x.
 */
@Configuration
public class McpStartupConfig {

    @Bean
    public CommandLineRunner registerExistingToolsOnStartup(
            RepositoryRepo repositoryRepo,
            McpToolRegistrationService registrationService) {
        return args -> repositoryRepo.findAllWithEndpoints()
                .forEach(registrationService::registerForRepository);
    }
}
