package com.rudhra.querypilot.ai;

import com.rudhra.querypilot.dto.IndexMetadata;
import com.rudhra.querypilot.dto.OptimizationValidationResult;
import com.rudhra.querypilot.dto.QueryAnalysisResponse;

import java.util.List;

public record QueryOptimizationContext(
        String sql,
        QueryAnalysisResponse.PlanSummary plan,
        List<QueryAnalysisResponse.Issue> issues,
        List<QueryAnalysisResponse.Join> joins,
        List<QueryAnalysisResponse.PlanOperation> operations,
        List<IndexMetadata> indexes,
        List<OptimizationValidationResult> validations
) {
}