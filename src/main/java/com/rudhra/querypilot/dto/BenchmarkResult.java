package com.rudhra.querypilot.dto;

import java.util.List;

public record BenchmarkResult(
        List<Double> executionTimesMs,
        double medianExecutionTimeMs,
        double averageExecutionTimeMs
) {
}