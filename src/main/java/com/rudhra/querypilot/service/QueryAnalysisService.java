package com.rudhra.querypilot.service;

import com.rudhra.querypilot.dto.*;
import com.rudhra.querypilot.dto.QueryAnalysisResponse.Issue;
import com.rudhra.querypilot.dto.QueryAnalysisResponse.PlanSummary;
import com.rudhra.querypilot.security.SqlSafetyValidator;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;


import java.util.ArrayList;
import java.util.List;

@Service
public class QueryAnalysisService {

    /** A Seq Scan that discards at least this many rows is treated as a HIGH severity issue. */
    private static final long SEQ_SCAN_HIGH_ROWS_THRESHOLD = 10_000;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final DatabaseMetadataService databaseMetadataService;
    private final OptimizationValidationService optimizationValidationService;
    private final SqlSafetyValidator sqlSafetyValidator;

    public QueryAnalysisService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                                DatabaseMetadataService databaseMetadataService,
                                OptimizationValidationService optimizationValidationService,
                                SqlSafetyValidator sqlSafetyValidator
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.databaseMetadataService = databaseMetadataService;
        this.optimizationValidationService = optimizationValidationService;
        this.sqlSafetyValidator = sqlSafetyValidator;
    }

    public ResponseEntity<QueryAnalysisResponse> analyzeQuery(
            QueryRequest request
    ) {
        sqlSafetyValidator.validate(request.getSql());

        String sqlStatement = request.getSql().trim();
        String explainQuery = "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + sqlStatement;
        String result = jdbcTemplate.queryForObject(explainQuery, String.class);

        // Parse JSON
        JsonNode rootNode = objectMapper.readTree(result);
        JsonNode analysisNode = rootNode.get(0);

        double executionTimeMs = analysisNode.path("Execution Time").asDouble();

        double planningTimeMs =analysisNode.path("Planning Time").asDouble();

        JsonNode planNode = analysisNode.path("Plan");

        double totalCost = planNode.path("Total Cost").asDouble();

        String rootNodeType = planNode.path("Node Type").asText();

        long estimatedRows = planNode.path("Plan Rows").asLong();

        long actualRows = planNode.path("Actual Rows").asLong();

        QueryAnalysisResponse.PlanSummary planSummary =
                new QueryAnalysisResponse.PlanSummary(
                        totalCost,
                        rootNodeType,
                        estimatedRows,
                        actualRows
                );
        List<QueryAnalysisResponse.Issue> issues = new ArrayList<>();
        analyzePlan(analysisNode.path("Plan"), issues);
        List<OptimizationCandidate> candidates = new ArrayList<>();

        List<OptimizationValidationResult> validations = new ArrayList<>();

        QueryAnalysisResponse response =
                new QueryAnalysisResponse(
                        executionTimeMs,
                        planningTimeMs,
                        planSummary,
                        issues,
                        findCandidates(issues, candidates),
                        validateCandidates(sqlStatement, executionTimeMs, totalCost, candidates, validations)
                );

        return ResponseEntity.ok(response);
    }

    private List<OptimizationValidationResult> validateCandidates(
            String sqlStatement,
            double executionTimeMs,
            double totalCost,
            List<OptimizationCandidate> candidates,
            List<OptimizationValidationResult> validations
    ) {
        for (OptimizationCandidate candidate : candidates) {
            OptimizationValidationResult validation =
                    optimizationValidationService.validate(
                            sqlStatement,
                            candidate,
                            totalCost
                    );

            validations.add(validation);
        }
        return validations;
    }


    private void analyzePlan(JsonNode planNode, List<QueryAnalysisResponse.Issue> issues) {
        String nodeType = planNode.path("Node Type").asText();

        if (nodeType.contains("Seq Scan")) {

            String relation = planNode.path("Relation Name").asText(null);
            long rowsRemoved = planNode.path("Rows Removed by Filter").asLong();
            long actualRows = planNode.path("Actual Rows").asLong();
            String filter = planNode.path("Filter").asText(null);
            long actualLoops = planNode.path("Actual Loops").asLong();

            long rowsExamined = actualRows + rowsRemoved;
            double selectivity = rowsExamined > 0 ? (double) actualRows / rowsExamined : 1.0;

            if (nodeType.contains("Seq Scan") && rowsRemoved > 1000 && selectivity < 0.05) {
                issues.add(
                        new QueryAnalysisResponse.Issue(
                                "INEFFICIENT_SEQUENTIAL_SCAN",
                                "HIGH",
                                relation,
                                rowsRemoved,
                                actualRows,
                                actualLoops,
                                actualRows * actualLoops,
                                filter
                        )
                );
                List<IndexMetadata> indexes = databaseMetadataService.getIndexes("orders");
                System.out.println(indexes);
            }
        }

        JsonNode childPlans = planNode.path("Plans");
        if (childPlans.isArray()) {
            for (JsonNode childPlan : childPlans) {
                analyzePlan(childPlan, issues);
            }
        }
    }
    private String extractFilterColumn(String filter) {
        if (filter == null || filter.isBlank())
        {
            return null;
        }
        String normalizedFilter = filter
                .replace("(", "")
                .replace(")", "")
                .trim();

        if (normalizedFilter.contains("=")) {
            return normalizedFilter
                    .split("=")[0]
                    .trim();
        }
        return null;
    }
    private boolean isColumnIndexed(String column, List<IndexMetadata> indexes) {
        return indexes.stream()
                .anyMatch(index ->
                        !index.columns().isEmpty()
                                && index.columns().get(0).equalsIgnoreCase(column)
                );
    }
    private List<OptimizationCandidate> findCandidates(List<Issue> issues, List<OptimizationCandidate> candidates) {
        for (QueryAnalysisResponse.Issue issue : issues) {
            if (!"INEFFICIENT_SEQUENTIAL_SCAN".equals(issue.type())) {
                continue;
            }
            String filterColumn = extractFilterColumn(issue.filter());
            if (filterColumn == null) {
                continue;
            }
            List<IndexMetadata> indexes = databaseMetadataService.getIndexes(issue.relation());

            boolean indexed = isColumnIndexed(filterColumn, indexes);
            if (!indexed) {
                String indexName = "idx_" + issue.relation() + "_" + filterColumn;
                String proposedSql = "CREATE INDEX " + indexName + " ON " + issue.relation() +
                        " (" + filterColumn + ")";

                candidates.add(
                        new OptimizationCandidate(
                                "CREATE_INDEX",
                                issue.relation(),
                                List.of(filterColumn),
                                proposedSql,
                                "Highly selective filter is causing an inefficient sequential scan and no existing index covers the filter column."
                        )
                );
            }

        }

        return candidates;
    }

}