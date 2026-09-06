package com.rudhra.querypilot.service;

import com.rudhra.querypilot.ai.OptimizationAdvisor;
import com.rudhra.querypilot.ai.OptimizationRecommendationValidator;
import com.rudhra.querypilot.ai.OptimizationSqlGenerator;
import com.rudhra.querypilot.ai.QueryOptimizationContext;
import com.rudhra.querypilot.ai.AiOptimizationAdvice;
import com.rudhra.querypilot.dto.IndexMetadata;
import com.rudhra.querypilot.dto.OptimizationCandidate;
import com.rudhra.querypilot.dto.OptimizationValidationResult;
import com.rudhra.querypilot.dto.QueryAnalysisResponse;
import com.rudhra.querypilot.dto.QueryRequest;
import com.rudhra.querypilot.security.SqlSafetyValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

@Service
public class QueryAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(QueryAnalysisService.class);

    private static final long SEQ_SCAN_HIGH_ROWS_THRESHOLD = 10_000;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final DatabaseMetadataService databaseMetadataService;
    private final OptimizationValidationService optimizationValidationService;
    private final SqlSafetyValidator sqlSafetyValidator;
    private final OptimizationAdvisor optimizationAdvisor;
    private final OptimizationRecommendationValidator recommendationValidator;
    private final OptimizationSqlGenerator sqlGenerator;

    public QueryAnalysisService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            DatabaseMetadataService databaseMetadataService,
            OptimizationValidationService optimizationValidationService,
            SqlSafetyValidator sqlSafetyValidator,
            OptimizationAdvisor optimizationAdvisor,
            OptimizationRecommendationValidator recommendationValidator,
            OptimizationSqlGenerator sqlGenerator
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.databaseMetadataService = databaseMetadataService;
        this.optimizationValidationService = optimizationValidationService;
        this.sqlSafetyValidator = sqlSafetyValidator;
        this.optimizationAdvisor = optimizationAdvisor;
        this.recommendationValidator = recommendationValidator;
        this.sqlGenerator = sqlGenerator;
    }

    public ResponseEntity<QueryAnalysisResponse> analyzeQuery(QueryRequest request) {
        String sql = validateAndGetSql(request);

        JsonNode analysisNode = executeExplain(sql);
        QueryAnalysisResponse.PlanSummary plan = extractPlanSummary(analysisNode);

        List<QueryAnalysisResponse.Issue> issues = analyzeIssues(analysisNode);
        List<QueryAnalysisResponse.Join> joins = analyzeJoins(analysisNode);
        List<QueryAnalysisResponse.PlanOperation> operations = analyzeOperations(analysisNode);
        List<OptimizationCandidate> candidates = findCandidates(issues);

        List<OptimizationValidationResult> validations = validateCandidates(sql, plan.totalCost(), candidates);

        AiOptimizationAdvice aiRecommendation = null;
        List<OptimizationValidationResult> aiValidations = List.of();

        try {
            aiRecommendation = generateAiRecommendation(sql, plan, issues, joins, operations, validations);
            List<OptimizationCandidate> aiCandidates = convertAiRecommendations(aiRecommendation);
            aiValidations = validateAiCandidates(sql, plan.totalCost(), aiCandidates, validations);
            processAiRecommendations(aiRecommendation);
        } catch (Exception exception) {
            // The deterministic analysis above (plan, issues, candidates, benchmarks) already
            // succeeded and is independently useful, so an AI failure (Groq outage, malformed
            // response, an unsupported recommendation type) should not fail the whole request -
            // degrade to "no AI recommendation" instead of a 500.
            log.warn("AI optimization advice unavailable, returning deterministic analysis only", exception);
            aiRecommendation = null;
            aiValidations = List.of();
        }

        return ResponseEntity.ok(buildResponse(analysisNode, plan, issues, joins, operations,
                candidates, validations, aiRecommendation, aiValidations));
    }

    // ---------------------------------------------------------
    // SQL validation
    // ---------------------------------------------------------

    private String validateAndGetSql(QueryRequest request) {
        sqlSafetyValidator.validate(request.getSql());
        return request.getSql().trim();
    }

    // ---------------------------------------------------------
    // PostgreSQL EXPLAIN
    // ---------------------------------------------------------

    private JsonNode executeExplain(String sql) {
        String explainQuery = "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + sql;
        String result = jdbcTemplate.queryForObject(explainQuery, String.class);
        return objectMapper.readTree(result).get(0);
    }

    // ---------------------------------------------------------
    // Plan parsing
    // ---------------------------------------------------------

    private QueryAnalysisResponse.PlanSummary extractPlanSummary(JsonNode analysisNode) {
        JsonNode planNode = analysisNode.path("Plan");

        double totalCost = planNode.path("Total Cost").asDouble();
        String rootNodeType = planNode.path("Node Type").asText();
        long estimatedRows = planNode.path("Plan Rows").asLong();
        long actualRows = planNode.path("Actual Rows").asLong();

        return new QueryAnalysisResponse.PlanSummary(totalCost, rootNodeType, estimatedRows, actualRows);
    }

    // ---------------------------------------------------------
    // Issue analysis
    // ---------------------------------------------------------

    private List<QueryAnalysisResponse.Issue> analyzeIssues(JsonNode analysisNode) {
        List<QueryAnalysisResponse.Issue> issues = new ArrayList<>();
        analyzePlan(analysisNode.path("Plan"), issues);
        return issues;
    }

    private void analyzePlan(JsonNode planNode, List<QueryAnalysisResponse.Issue> issues) {
        String nodeType = planNode.path("Node Type").asText();

        if (nodeType.contains("Seq Scan")) {
            analyzeSequentialScan(planNode, issues);
        }

        analyzeChildPlans(planNode, issues);
    }

    private void analyzeSequentialScan(JsonNode planNode, List<QueryAnalysisResponse.Issue> issues) {
        String relation = planNode.path("Relation Name").asText(null);
        long rowsRemoved = planNode.path("Rows Removed by Filter").asLong();
        long actualRows = planNode.path("Actual Rows").asLong();
        long actualLoops = planNode.path("Actual Loops").asLong();
        String filter = planNode.path("Filter").asText(null);

        long rowsExamined = actualRows + rowsRemoved;
        double selectivity = calculateSelectivity(actualRows, rowsExamined);

        if (isInefficientSequentialScan(rowsRemoved, selectivity)) {
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
        }
    }

    private double calculateSelectivity(long actualRows, long rowsExamined) {
        if (rowsExamined <= 0) {
            return 1.0;
        }

        return (double) actualRows / rowsExamined;
    }

    private boolean isInefficientSequentialScan(long rowsRemoved, double selectivity) {
        return rowsRemoved > SEQ_SCAN_HIGH_ROWS_THRESHOLD && selectivity < 0.05;
    }

    private void analyzeChildPlans(JsonNode planNode, List<QueryAnalysisResponse.Issue> issues) {
        JsonNode childPlans = planNode.path("Plans");

        if (!childPlans.isArray()) {
            return;
        }

        for (JsonNode childPlan : childPlans) {
            analyzePlan(childPlan, issues);
        }
    }

    // ---------------------------------------------------------
    // Candidate generation
    // ---------------------------------------------------------

    private List<OptimizationCandidate> findCandidates(List<QueryAnalysisResponse.Issue> issues) {
        List<OptimizationCandidate> candidates = new ArrayList<>();

        for (QueryAnalysisResponse.Issue issue : issues) {
            if (!isSequentialScanIssue(issue)) {
                continue;
            }

            addIndexCandidate(issue, candidates);
        }

        return candidates;
    }

    private boolean isSequentialScanIssue(QueryAnalysisResponse.Issue issue) {
        return "INEFFICIENT_SEQUENTIAL_SCAN".equals(issue.type());
    }

    private void addIndexCandidate(QueryAnalysisResponse.Issue issue, List<OptimizationCandidate> candidates) {
        String filterColumn = extractFilterColumn(issue.filter());

        if (filterColumn == null) {
            return;
        }

        List<IndexMetadata> indexes = databaseMetadataService.getIndexes(issue.relation());

        if (isColumnIndexed(filterColumn, indexes)) {
            return;
        }

        String indexName = "idx_" + issue.relation() + "_" + filterColumn;

        String proposedSql = "CREATE INDEX " + indexName + " ON " + issue.relation() + " (" + filterColumn + ")";

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

    private String extractFilterColumn(String filter) {
        if (filter == null || filter.isBlank()) {
            return null;
        }

        String normalizedFilter = filter.replace("(", "").replace(")", "").trim();

        if (!normalizedFilter.contains("=")) {
            return null;
        }

        return normalizedFilter.split("=")[0].trim();
    }

    private boolean isColumnIndexed(String column, List<IndexMetadata> indexes) {
        return indexes.stream()
                .anyMatch(index -> !index.columns().isEmpty() && index.columns().get(0).equalsIgnoreCase(column));
    }

    // ---------------------------------------------------------
    // Deterministic candidate validation
    // ---------------------------------------------------------

    private List<OptimizationValidationResult> validateCandidates(
            String sql,
            double totalCost,
            List<OptimizationCandidate> candidates
    ) {
        List<OptimizationValidationResult> validations = new ArrayList<>();

        for (OptimizationCandidate candidate : candidates) {
            OptimizationValidationResult validation = optimizationValidationService.validate(sql, candidate, totalCost);
            validations.add(validation);
        }

        return validations;
    }

    // ---------------------------------------------------------
    // AI recommendation
    // ---------------------------------------------------------

    private AiOptimizationAdvice generateAiRecommendation(
            String sql,
            QueryAnalysisResponse.PlanSummary plan,
            List<QueryAnalysisResponse.Issue> issues,
            List<QueryAnalysisResponse.Join> joins, List<QueryAnalysisResponse.PlanOperation> operations, List<OptimizationValidationResult> validations) {
        QueryOptimizationContext context = new QueryOptimizationContext(sql, plan, issues,
                joins, operations, getRelevantIndexes(issues), validations);
        return optimizationAdvisor.advise(context);
    }

    private List<IndexMetadata> getRelevantIndexes(List<QueryAnalysisResponse.Issue> issues) {
        if (issues.isEmpty()) {
            return List.of();
        }

        return databaseMetadataService.getIndexes(issues.get(0).relation());
    }

    // ---------------------------------------------------------
    // AI recommendation validation + SQL generation
    // ---------------------------------------------------------

    private void processAiRecommendations(AiOptimizationAdvice aiRecommendation) {
        List<String> generatedSql = new ArrayList<>();

        for (AiOptimizationAdvice.Recommendation recommendation : aiRecommendation.recommendations()) {
            recommendationValidator.validate(recommendation);

            if ("CREATE_INDEX".equals(recommendation.type())) {
                String sql = sqlGenerator.generate(recommendation);
                generatedSql.add(sql);
            }
        }

        if (!generatedSql.isEmpty()) {
            System.out.println("Validated AI SQL: " + generatedSql);
        }
    }

    // ---------------------------------------------------------
    // Response construction
    // ---------------------------------------------------------

    private QueryAnalysisResponse buildResponse(
            JsonNode analysisNode,
            QueryAnalysisResponse.PlanSummary plan,
            List<QueryAnalysisResponse.Issue> issues,
            List<QueryAnalysisResponse.Join> joins,
            List<QueryAnalysisResponse.PlanOperation> operations,
            List<OptimizationCandidate> candidates,
            List<OptimizationValidationResult> validations,
            AiOptimizationAdvice aiRecommendation,
            List<OptimizationValidationResult> aiValidations
    ) {
        double executionTimeMs = analysisNode.path("Execution Time").asDouble();
        double planningTimeMs = analysisNode.path("Planning Time").asDouble();

        return new QueryAnalysisResponse(
                executionTimeMs,
                planningTimeMs,
                plan,
                issues,
                joins,
                operations,
                candidates,
                validations,
                aiRecommendation,
                aiValidations
        );
    }
    private List<OptimizationCandidate> convertAiRecommendations(AiOptimizationAdvice aiRecommendation) {
        List<OptimizationCandidate> candidates = new ArrayList<>();
        for (AiOptimizationAdvice.Recommendation recommendation : aiRecommendation.recommendations()) {
            recommendationValidator.validate(recommendation);
            if (!"CREATE_INDEX".equals(recommendation.type())) {
                continue;
            }
            String proposedSql = sqlGenerator.generate(recommendation);
            candidates.add(
                    new OptimizationCandidate(
                            recommendation.type(),
                            recommendation.table(),
                            recommendation.columns(),
                            proposedSql,
                            recommendation.reasoning()
                    )
            );
        }
        return candidates;
    }

    private List<OptimizationValidationResult> validateAiCandidates(
            String sql,
            double totalCost,
            List<OptimizationCandidate> candidates,
            List<OptimizationValidationResult> deterministicValidations
    ) {
        List<OptimizationValidationResult> results = new ArrayList<>();
        for (OptimizationCandidate candidate : candidates) {
            OptimizationValidationResult reused = findMatchingValidation(candidate, deterministicValidations);
            results.add(
                    reused != null
                            ? reused
                            : optimizationValidationService.validate(sql, candidate, totalCost)
            );
        }
        return results;
    }

    /**
     * The AI is handed the deterministic candidates/validations as evidence and commonly
     * proposes the exact same CREATE_INDEX. Re-running the sandbox drop/recreate/benchmark
     * cycle for an index that was just validated is pure waste, so reuse that result instead.
     */
    private OptimizationValidationResult findMatchingValidation(
            OptimizationCandidate candidate,
            List<OptimizationValidationResult> validations
    ) {
        return validations.stream()
                .filter(validation -> isSameCandidate(validation.candidate(), candidate))
                .findFirst()
                .orElse(null);
    }

    private boolean isSameCandidate(OptimizationCandidate a, OptimizationCandidate b) {
        if (!a.type().equals(b.type()) || !a.table().equalsIgnoreCase(b.table())) {
            return false;
        }

        List<String> columnsA = a.columns().stream().map(String::toLowerCase).toList();
        List<String> columnsB = b.columns().stream().map(String::toLowerCase).toList();
        return columnsA.equals(columnsB);
    }
    private List<QueryAnalysisResponse.Join> analyzeJoins(JsonNode analysisNode) {

        List<QueryAnalysisResponse.Join> joins = new ArrayList<>();
        collectJoins(analysisNode.path("Plan"), joins);
        return joins;
    }
    private void collectJoins(JsonNode planNode, List<QueryAnalysisResponse.Join> joins) {
        String nodeType = planNode.path("Node Type").asText();
        if (isJoinNode(nodeType)) {
            joins.add(createJoin(planNode));
        }
        JsonNode childPlans = planNode.path("Plans");
        if (!childPlans.isArray()) {
            return;
        }
        for (JsonNode childPlan : childPlans) {
            collectJoins(childPlan, joins);
        }
    }
    private boolean isJoinNode(String nodeType) {
        return "Nested Loop".equals(nodeType)
                || "Hash Join".equals(nodeType)
                || "Merge Join".equals(nodeType);
    }
    private QueryAnalysisResponse.Join createJoin(JsonNode planNode) {
        List<String> relations = extractJoinRelations(planNode);
        return new QueryAnalysisResponse.Join(
                planNode.path("Node Type").asText(),
                planNode.path("Plan Rows").asLong(),
                planNode.path("Actual Rows").asLong(),
                planNode.path("Actual Loops").asLong(),
                planNode.path("Total Cost").asDouble(),
                relations
        );
    }
    private List<String> extractJoinRelations(JsonNode planNode) {
        List<String> relations = new ArrayList<>();
        collectRelations(planNode, relations);
        return relations.stream().distinct().toList();
    }
    private void collectRelations(JsonNode node, List<String> relations) {
        String relation = node.path("Relation Name").asText(null);
        if (relation != null && !relation.isBlank()) {
            relations.add(relation);
        }
        JsonNode childPlans = node.path("Plans");
        if (!childPlans.isArray()) {
            return;
        }
        for (JsonNode child : childPlans) {
            collectRelations(child,relations);
        }
    }
    private List<QueryAnalysisResponse.PlanOperation> analyzeOperations(JsonNode analysisNode) {
        List<QueryAnalysisResponse.PlanOperation> operations = new ArrayList<>();
        collectOperations(analysisNode.path("Plan"), operations);
        return operations;
    }
    private void collectOperations(JsonNode planNode, List<QueryAnalysisResponse.PlanOperation> operations) {
        String nodeType = planNode.path("Node Type").asText();
        if (isInterestingOperation(nodeType)) {
            operations.add(
                    new QueryAnalysisResponse.PlanOperation(
                            nodeType,
                            determineOperationSeverity(planNode),
                            planNode.path("Relation Name").asText(null),
                            planNode.path("Plan Rows").asLong(),
                            planNode.path("Actual Rows").asLong(),
                            planNode.path("Actual Loops").asLong(),
                            planNode.path("Total Cost").asDouble()
                    )
            );
        }
        JsonNode childPlans = planNode.path("Plans");
        if (!childPlans.isArray()) {
            return;
        }
        for (JsonNode childPlan : childPlans) {
            collectOperations(childPlan, operations);
        }
    }
    private boolean isInterestingOperation(String nodeType) {

        return nodeType.contains("Sort")
                || nodeType.contains("Aggregate");
    }
    private String determineOperationSeverity(JsonNode planNode) {
        String nodeType = planNode.path("Node Type").asText();
        long actualRows = planNode.path("Actual Rows").asLong();
        double totalCost = planNode.path("Total Cost").asDouble();
        if (nodeType.contains("Sort") && totalCost > 10_000) {
            return "HIGH";
        }
        if (nodeType.contains("Aggregate") && actualRows > 100_000) {
            return "HIGH";
        }
        return "MEDIUM";
    }
}
