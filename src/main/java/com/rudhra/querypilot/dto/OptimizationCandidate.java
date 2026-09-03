package com.rudhra.querypilot.dto;

import java.util.List;

public record OptimizationCandidate(
        String type,
        String table,
        List<String> columns,
        String proposedSql,
        String reason
) {
}