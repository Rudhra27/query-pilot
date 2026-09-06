package com.rudhra.querypilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "querypilot.ai")
public record AiProperties(
        boolean enabled,
        String apiKey,
        String baseUrl,
        String model
) {
}