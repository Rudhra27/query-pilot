package com.rudhra.querypilot.ai;

import com.rudhra.querypilot.ai.AiOptimizationAdvice;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class OptimizationSqlGenerator {

    public String generate(AiOptimizationAdvice.Recommendation recommendation) {

        if (!"CREATE_INDEX".equals(recommendation.type())) {
            throw new IllegalArgumentException("SQL generation is only supported for CREATE_INDEX");
        }

        validateIdentifier(recommendation.table());

        List<String> columns = recommendation.columns();

        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("CREATE_INDEX requires at least one column");
        }

        for (String column : columns) {
            validateIdentifier(column);
        }

        String indexName =
                "idx_" +
                        recommendation.table() +
                        "_" +
                        String.join("_", columns);

        return "CREATE INDEX " +
                indexName +
                " ON " +
                recommendation.table() +
                " (" +
                String.join(", ", columns) +
                ")";
    }

    private void validateIdentifier(String identifier) {

        if (identifier == null || !identifier.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {

            throw new IllegalArgumentException("Invalid SQL identifier: " + identifier);
        }
    }
}