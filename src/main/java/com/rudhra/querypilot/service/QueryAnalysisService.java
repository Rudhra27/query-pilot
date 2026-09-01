package com.rudhra.querypilot.service;

import com.rudhra.querypilot.dto.QueryAnalysisResponse;
import com.rudhra.querypilot.dto.QueryAnalysisResponse.Issue;
import com.rudhra.querypilot.dto.QueryAnalysisResponse.PlanSummary;
import com.rudhra.querypilot.dto.QueryRequest;
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

    public QueryAnalysisService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public ResponseEntity<QueryAnalysisResponse> analyzeQuery(
            QueryRequest request
    ) {
        String sqlStatement = request.getSql().trim();

        String explainQuery =
                "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) "
                        + sqlStatement;

        String result = jdbcTemplate.queryForObject(
                explainQuery,
                String.class
        );

        // Parse JSON
        JsonNode rootNode = objectMapper.readTree(result);

        JsonNode analysisNode = rootNode.get(0);

        double executionTimeMs =
                analysisNode.path("Execution Time").asDouble();

        double planningTimeMs =
                analysisNode.path("Planning Time").asDouble();

        JsonNode planNode = analysisNode.path("Plan");

        double totalCost =
                planNode.path("Total Cost").asDouble();

        String rootNodeType =
                planNode.path("Node Type").asText();

        long estimatedRows =
                planNode.path("Plan Rows").asLong();

        long actualRows =
                planNode.path("Actual Rows").asLong();

        QueryAnalysisResponse.PlanSummary planSummary =
                new QueryAnalysisResponse.PlanSummary(
                        totalCost,
                        rootNodeType,
                        estimatedRows,
                        actualRows
                );
        List<QueryAnalysisResponse.Issue> issues = new ArrayList<>();
        analyzePlan(analysisNode.path("Plan"), issues);

        QueryAnalysisResponse response =
                new QueryAnalysisResponse(
                        executionTimeMs,
                        planningTimeMs,
                        planSummary,
                        issues
                );

        return ResponseEntity.ok(response);
    }

    private void analyzePlan(
            JsonNode planNode,
            List<QueryAnalysisResponse.Issue> issues
    ) {

        String nodeType = planNode
                .path("Node Type")
                .asText();

        if (nodeType.contains("Seq Scan")) {

            String relation = planNode
                    .path("Relation Name")
                    .asText(null);

            long rowsRemoved = planNode
                    .path("Rows Removed by Filter")
                    .asLong();

            issues.add(
                    new QueryAnalysisResponse.Issue(
                            "SEQUENTIAL_SCAN",
                            "HIGH",
                            relation,
                            rowsRemoved
                    )
            );
        }

        JsonNode childPlans = planNode.path("Plans");

        if (childPlans.isArray()) {

            for (JsonNode childPlan : childPlans) {

                analyzePlan(childPlan, issues);

            }
        }
    }
}
