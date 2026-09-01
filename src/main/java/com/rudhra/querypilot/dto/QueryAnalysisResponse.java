package com.rudhra.querypilot.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record QueryAnalysisResponse(

        double executionTimeMs,
        double planningTimeMs,
        PlanSummary plan,
        List<Issue> issues

) {
    public record PlanSummary(
            double totalCost,
            String rootNodeType,
            Long estimatedRows,
            Long actualRows

    ) {
    }

    public record Issue(
            String type,
            String severity,
            String relation,
            Long rowsRemovedByFilter
    ) {
    }
}
//
//Sample full Response:
//        [
//        {
//        "Plan": {
//        "Node Type": "Gather",
//        "Parallel Aware": false,
//        "Async Capable": false,
//        "Startup Cost": 1000.00,
//        "Total Cost": 15059.33,
//        "Plan Rows": 10,
//        "Plan Width": 38,
//        "Actual Startup Time": 4.176,
//        "Actual Total Time": 44.650,
//        "Actual Rows": 10,
//        "Actual Loops": 1,
//        "Workers Planned": 2,
//        "Workers Launched": 2,
//        "Single Copy": false,
//        "Shared Hit Blocks": 288,
//        "Shared Read Blocks": 8562,
//        "Shared Dirtied Blocks": 0,
//        "Shared Written Blocks": 0,
//        "Local Hit Blocks": 0,
//        "Local Read Blocks": 0,
//        "Local Dirtied Blocks": 0,
//        "Local Written Blocks": 0,
//        "Temp Read Blocks": 0,
//        "Temp Written Blocks": 0,
//        "Plans": [
//        {
//        "Node Type": "Seq Scan",
//        "Parent Relationship": "Outer",
//        "Parallel Aware": true,
//        "Async Capable": false,
//        "Relation Name": "orders",
//        "Alias": "orders",
//        "Startup Cost": 0.00,
//        "Total Cost": 14058.33,
//        "Plan Rows": 4,
//        "Plan Width": 38,
//        "Actual Startup Time": 6.275,
//        "Actual Total Time": 29.800,
//        "Actual Rows": 3,
//        "Actual Loops": 3,
//        "Filter": "(customer_id = 50000)",
//        "Rows Removed by Filter": 333330,
//        "Shared Hit Blocks": 288,
//        "Shared Read Blocks": 8562,
//        "Shared Dirtied Blocks": 0,
//        "Shared Written Blocks": 0,
//        "Local Hit Blocks": 0,
//        "Local Read Blocks": 0,
//        "Local Dirtied Blocks": 0,
//        "Local Written Blocks": 0,
//        "Temp Read Blocks": 0,
//        "Temp Written Blocks": 0,
//        "Workers": [
//        ]
//        }
//        ]
//        },
//        "Planning": {
//        "Shared Hit Blocks": 0,
//        "Shared Read Blocks": 0,
//        "Shared Dirtied Blocks": 0,
//        "Shared Written Blocks": 0,
//        "Local Hit Blocks": 0,
//        "Local Read Blocks": 0,
//        "Local Dirtied Blocks": 0,
//        "Local Written Blocks": 0,
//        "Temp Read Blocks": 0,
//        "Temp Written Blocks": 0
//        },
//        "Planning Time": 0.389,
//        "Triggers": [
//        ],
//        "Execution Time": 44.715
//        }