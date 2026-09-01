package com.rudhra.querypilot.controller;

import com.rudhra.querypilot.dto.QueryAnalysisResponse;
import com.rudhra.querypilot.dto.QueryRequest;
import com.rudhra.querypilot.service.QueryAnalysisService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class QueryAnalysisController {

    private final QueryAnalysisService queryAnalysisService;

    public QueryAnalysisController(QueryAnalysisService queryAnalysisService) {
        this.queryAnalysisService = queryAnalysisService;
    }

    @PostMapping("/api/v1/query-analysis")
    public ResponseEntity<QueryAnalysisResponse> analyzeQuery(@Valid @RequestBody QueryRequest request) {
        return queryAnalysisService.analyzeQuery(request);
    }
}
