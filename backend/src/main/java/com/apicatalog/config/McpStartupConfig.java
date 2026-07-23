package com.apicatalog.config;

import com.apicatalog.repository.RepositoryRepo;
import com.apicatalog.service.mcp.McpToolRegistrationService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Rebuilds MCP tool registrations on every startup.
 * McpSyncServer holds tools in memory only, so they must be re-registered
 * after each restart.
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
