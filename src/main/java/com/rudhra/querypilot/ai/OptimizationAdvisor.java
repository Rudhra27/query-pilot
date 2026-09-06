package com.rudhra.querypilot.ai;

public interface OptimizationAdvisor {
    AiOptimizationAdvice advise(QueryOptimizationContext context);
}