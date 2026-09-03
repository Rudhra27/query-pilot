package com.rudhra.querypilot.dto;

public record OptimizationValidationResult(
        OptimizationCandidate candidate,
        double beforeBenchmark,
        double afterBenchmark,
        double beforeTotalCost,
        double afterTotalCost,
        double executionTimeImprovementPercent,
        double costImprovementPercent,
        boolean improved
) {
}