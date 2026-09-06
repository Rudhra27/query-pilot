package com.rudhra.querypilot.service;

import com.rudhra.querypilot.dto.BenchmarkResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.rudhra.querypilot.dto.OptimizationCandidate;
import com.rudhra.querypilot.dto.OptimizationValidationResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class OptimizationValidationService {

    private final SandboxDatabaseService sandboxDatabaseService;
    private final ObjectMapper objectMapper;
    private final BenchmarkService benchmarkService;
    private final JdbcTemplate jdbcTemplate;

    public OptimizationValidationService(
            SandboxDatabaseService sandboxDatabaseService,
            ObjectMapper objectMapper, BenchmarkService benchmarkService, JdbcTemplate jdbcTemplate
    ) {
        this.sandboxDatabaseService = sandboxDatabaseService;
        this.objectMapper = objectMapper;
        this.benchmarkService = benchmarkService;
        this.jdbcTemplate = jdbcTemplate;
    }

    public OptimizationValidationResult validate(String originalSql, OptimizationCandidate candidate,
             double beforeTotalCost) {

        BenchmarkResult beforeBenchmark = benchmarkService.benchmark(jdbcTemplate, originalSql);
        // 1. Create a fresh sandbox clone
        sandboxDatabaseService.createFreshSandbox();

        // 2. Connect to sandbox
        JdbcTemplate sandboxJdbcTemplate = sandboxDatabaseService.getSandboxJdbcTemplate();

        // 3. Apply optimization ONLY to sandbox
        sandboxJdbcTemplate.execute(candidate.proposedSql());

        BenchmarkResult afterBenchmark = benchmarkService.benchmark(sandboxJdbcTemplate, originalSql);
        // 4. Run EXPLAIN ANALYZE again
        String explainSql = "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + originalSql;
        String result = sandboxJdbcTemplate.queryForObject(explainSql, String.class);

        try {
            JsonNode rootNode = objectMapper.readTree(result);

            JsonNode analysisNode = rootNode.get(0);
            double beforeExecutionTime = beforeBenchmark.medianExecutionTimeMs();
            double afterExecutionTime = afterBenchmark.medianExecutionTimeMs();
            double afterTotalCost = analysisNode.path("Plan").path("Total Cost").asDouble();

            double improvementPercent = ((beforeExecutionTime - afterExecutionTime) / beforeExecutionTime) * 100;
            double costImprovementPercent = ((beforeTotalCost - afterTotalCost) / beforeTotalCost) * 100;
            boolean improved = improvementPercent >= 5.0;

            return new OptimizationValidationResult(
                    candidate,
                    beforeExecutionTime,
                    afterExecutionTime,
                    beforeTotalCost,
                    afterTotalCost,
                    improvementPercent,
                    costImprovementPercent,
                    improved
            );

        } catch (Exception exception) {

            throw new RuntimeException(
                    "Failed to parse sandbox execution plan",
                    exception
            );
        }
    }
}