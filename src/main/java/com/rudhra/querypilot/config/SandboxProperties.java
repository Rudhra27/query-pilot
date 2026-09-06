package com.rudhra.querypilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "querypilot.sandbox")
public record SandboxProperties(
        String adminUrl,
        String databaseName,
        String baselineDatabaseName,
        String jdbcUrl,
        String adminUsername,
        String adminPassword,
        String username,
        String password
) {
}