package com.rudhra.querypilot.ai;

import java.util.List;

public record AiOptimizationAdvice(
        String summary,
        List<Recommendation> recommendations
) {

    public record Recommendation(
            String type,
            String table,
            List<String> columns,
            String reasoning
    ) {
    }
}