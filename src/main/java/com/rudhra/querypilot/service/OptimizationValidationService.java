package com.rudhra.querypilot.service;

import com.rudhra.querypilot.dto.BenchmarkResult;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.rudhra.querypilot.dto.OptimizationCandidate;
import com.rudhra.querypilot.dto.OptimizationValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;

@Service
public class OptimizationValidationService {

    private static final Logger log = LoggerFactory.getLogger(OptimizationValidationService.class);
    private static final int EVICTION_POLL_MAX_ATTEMPTS = 20;
    private static final long EVICTION_POLL_INTERVAL_MS = 100L;

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

        // CREATE DATABASE ... TEMPLATE requires zero other connections to the
        // template (primary) database. The pool behind `jdbcTemplate` just
        // returned a connection from the benchmark above but keeps it open
        // and idle for reuse. Locally, SandboxDatabaseService's admin role is
        // a real Postgres superuser and can pg_terminate_backend anyone's
        // session to clear this; on managed hosts (e.g. Render) that admin
        // role is not a superuser and cannot terminate a *different* role's
        // backend (querypilot_readonly's pool here) - only its own. So the
        // pool has to release its own idle connection instead of being
        // force-killed from outside.
        evictIdlePrimaryConnections();

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

    private void evictIdlePrimaryConnections() {
        DataSource dataSource = jdbcTemplate.getDataSource();

        if (!(dataSource instanceof HikariDataSource hikariDataSource)) {
            log.warn("Primary DataSource is not a HikariDataSource ({}); cannot evict pooled connections before sandbox clone",
                    dataSource == null ? "null" : dataSource.getClass().getName());
            return;
        }

        HikariPoolMXBean pool = hikariDataSource.getHikariPoolMXBean();
        log.info("Evicting primary pool before sandbox clone - before: active={} idle={} total={}",
                pool.getActiveConnections(), pool.getIdleConnections(), pool.getTotalConnections());

        pool.softEvictConnections();

        // softEvictConnections() closes idle connections on Hikari's own
        // housekeeper thread - it isn't necessarily done by the time this
        // method returns. Poll briefly rather than assume the race is won,
        // since CREATE DATABASE ... TEMPLATE needs the pool truly empty.
        for (int attempt = 1; attempt <= EVICTION_POLL_MAX_ATTEMPTS; attempt++) {
            if (pool.getTotalConnections() == 0) {
                log.info("Primary pool empty after {} attempt(s)", attempt);
                return;
            }
            try {
                Thread.sleep(EVICTION_POLL_INTERVAL_MS);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        log.warn("Primary pool still not empty after eviction - active={} idle={} total={}",
                pool.getActiveConnections(), pool.getIdleConnections(), pool.getTotalConnections());
    }
}