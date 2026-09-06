package com.rudhra.querypilot.ai;

import com.rudhra.querypilot.ai.AiOptimizationAdvice;
import com.rudhra.querypilot.dto.IndexMetadata;
import com.rudhra.querypilot.service.DatabaseMetadataService;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class OptimizationRecommendationValidator {

    private final DatabaseMetadataService databaseMetadataService;

    public OptimizationRecommendationValidator(DatabaseMetadataService databaseMetadataService) {
        this.databaseMetadataService = databaseMetadataService;
    }

    public void validate(AiOptimizationAdvice.Recommendation recommendation) {
        if (recommendation == null) {
            throw new IllegalArgumentException("AI recommendation cannot be null");
        }

        String type = recommendation.type();

        if ("NO_CHANGE".equals(type)) {
            return;
        }

        if (!"CREATE_INDEX".equals(type)) {
            throw new IllegalArgumentException("Unsupported AI optimization type: " + type);
        }

        String table = recommendation.table();

        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("Recommendation table cannot be empty");
        }

        List<String> columns = recommendation.columns();

        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("CREATE_INDEX recommendation must contain columns");
        }

        /*
         * Verify that the table actually contains
         * every column recommended by the LLM.
         */
        List<String> tableColumns = databaseMetadataService.getColumns(table);
        for (String column : columns) {
            boolean exists = tableColumns.stream().anyMatch(existing -> existing.equalsIgnoreCase(column));
            if (!exists) {
                throw new IllegalArgumentException("Column '" + column + "' does not exist on table '" + table + "'");
            }
        }

        /*
         * Don't recommend an index that already exists.
         */
        List<IndexMetadata> indexes = databaseMetadataService.getIndexes(table);

        boolean alreadyIndexed =
                indexes.stream()
                        .anyMatch(index ->
                                index.columns().size() == columns.size()
                                        && index.columns().stream()
                                        .map(String::toLowerCase)
                                        .toList()
                                        .equals(
                                                columns.stream()
                                                        .map(String::toLowerCase)
                                                        .toList()
                                        )
                        );

        if (alreadyIndexed) {
            throw new IllegalArgumentException("An index already exists for " + table + columns);
        }
    }
}