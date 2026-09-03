package com.rudhra.querypilot.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.rudhra.querypilot.dto.BenchmarkResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class BenchmarkService {

    private static final int DEFAULT_ITERATIONS = 5;
    private final ObjectMapper objectMapper;

    public BenchmarkService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public BenchmarkResult benchmark(JdbcTemplate jdbcTemplate, String sql) {

        List<Double> executionTimes = new ArrayList<>();
        String explainSql = "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + sql;
        for (int i = 0; i < DEFAULT_ITERATIONS; i++) {
            String result = jdbcTemplate.queryForObject(explainSql, String.class);

            double executionTime = extractExecutionTime(result);
            executionTimes.add(executionTime);
        }

        double median = calculateMedian(executionTimes);

        double average = executionTimes.stream()
                        .mapToDouble(Double::doubleValue)
                        .average()
                        .orElse(0.0);

        return new BenchmarkResult(
                executionTimes,
                median,
                average
        );
    }

    private double extractExecutionTime(String result) {
        try {
            JsonNode rootNode = objectMapper.readTree(result);
            return rootNode
                    .get(0)
                    .path("Execution Time")
                    .asDouble();

        } catch (Exception exception) {
            throw new RuntimeException(
                    "Failed to parse benchmark result",
                    exception
            );
        }
    }

    private double calculateMedian(List<Double> executionTimes) {
        List<Double> sortedTimes = new ArrayList<>(executionTimes);
        Collections.sort(sortedTimes);

        int size = sortedTimes.size();

        if (size % 2 == 1) {
            return sortedTimes.get(size / 2);
        }
        int middle = size / 2;
        return (sortedTimes.get(middle - 1) + sortedTimes.get(middle)) / 2.0;
    }
}