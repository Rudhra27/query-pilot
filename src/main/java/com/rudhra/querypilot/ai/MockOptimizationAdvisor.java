package com.rudhra.querypilot.ai;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Profile("mock-ai")
public class MockOptimizationAdvisor implements OptimizationAdvisor {

    @Override
    public AiOptimizationAdvice advise(QueryOptimizationContext context) {

        return new AiOptimizationAdvice(
                "The query appears to perform an inefficient sequential scan.",
                List.of(
                        new AiOptimizationAdvice.Recommendation(
                                "CREATE_INDEX",
                                "orders",
                                List.of("customer_id"),
                                "The query filters on customer_id while no existing index covers that column."
                        )
                )
        );
    }
}