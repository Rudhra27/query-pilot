package com.rudhra.querypilot.dto;

import java.util.List;

public record IndexMetadata(
        String indexName,
        List<String> columns,
        boolean unique,
        String indexDefinition
) {
}