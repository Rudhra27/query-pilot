package com.rudhra.querypilot.ai;

import com.rudhra.querypilot.config.AiProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

@Component
@Profile("!mock-ai")
public class GroqOptimizationAdvisor implements OptimizationAdvisor {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final AiProperties properties;

    public GroqOptimizationAdvisor(
            ObjectMapper objectMapper,
            AiProperties properties
    ) {
        this.objectMapper = objectMapper;
        this.properties = properties;

        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeader(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer " + properties.apiKey()
                )
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public AiOptimizationAdvice advise(QueryOptimizationContext context) {

        if (!properties.enabled()) {
            return new AiOptimizationAdvice("AI optimization is disabled.", List.of());
        }
        try {
            String contextJson = objectMapper.writeValueAsString(context);

            Map<String, Object> request = buildRequest(contextJson);

            JsonNode response = restClient.post()
                    .uri("/chat/completions")
                    .body(request)
                    .retrieve()
                    .body(JsonNode.class);

            String content = response
                    .path("choices")
                    .get(0)
                    .path("message")
                    .path("content")
                    .asText();

            return objectMapper.readValue(content, AiOptimizationAdvice.class);

        } catch (Exception exception) {
            throw new RuntimeException("Failed to generate Groq optimization advice", exception);
        }
    }

    private Map<String, Object> buildRequest(String contextJson) {

        return Map.of("model", properties.model(), "messages",
                List.of(
                        Map.of(
                                "role", "system",
                                "content", SYSTEM_PROMPT
                        ),

                        Map.of("role", "user",
                                "content",
                                """
                                Analyze this QueryPilot database evidence:
                                %s
                                """.formatted(contextJson)
                        )
                ),

                "reasoning_effort", "low",
                "response_format",
                Map.of(
                        "type", "json_schema",
                        "json_schema", Map.of(
                                "name", "query_optimization_advice",
                                "strict", true,
                                "schema", responseSchema()
                        )
                )
        );
    }

    private Map<String, Object> responseSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "summary", Map.of("type", "string"),
                        "recommendations",
                        Map.of(
                                "type", "array",
                                "items", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "type", Map.of(
                                                        "type", "string",
                                                        "enum", List.of(
                                                                "CREATE_INDEX",
                                                                "NO_CHANGE"
                                                        )
                                                ),
                                                "table", Map.of("type", "string"),
                                                "columns", Map.of(
                                                        "type", "array",
                                                        "items", Map.of("type", "string")),
                                                "reasoning", Map.of("type", "string")
                                        ),

                                        "required", List.of("type", "table", "columns", "reasoning"),
                                        "additionalProperties", false
                                )
                        )
                ),

                "required", List.of("summary", "recommendations"),
                "additionalProperties", false
        );
    }

    private static final String SYSTEM_PROMPT = """
            You are a PostgreSQL performance optimization expert.
            
            Analyze the provided SQL execution evidence and explain the most useful optimization.
            
            If there are no optimization candidates or validations:
            
            - Do NOT simply say that nothing is happening.
            - Identify the most significant bottleneck visible in the execution plan.
            - Explain why it may be expensive.
            - Clearly state that QueryPilot has not validated an automatic optimization for it.
            - Do not invent a specific index, rewrite, configuration change, or other fix.
            - Return NO_CHANGE as the recommendation type.
            
            IMPORTANT RULES:
            
            1. Base recommendations ONLY on the supplied evidence.
            2. Do not invent tables, columns, indexes, joins, or execution-plan details.
            3. Do not recommend an optimization unless the execution plan or validation evidence supports it.
            4. If an optimization candidate has been benchmarked and improved performance, prioritize that candidate.
            5. If a candidate made performance worse, do NOT recommend it.
            6. Return NO_CHANGE only when there is no validated or strongly supported optimization.
            7. Do NOT return NO_CHANGE together with another recommendation.
            8. Keep the explanation understandable to a software engineer who is not a PostgreSQL expert.
            9. Explain WHY the optimization helps.
            10. Distinguish between estimated planner cost and actual benchmark execution time.
            
            Execution evidence:
            
            SQL:
            %s
            
            Plan:
            %s
            
            Issues:
            %s
            
            Joins:
            %s
            
            Operations:
            %s
            
            Existing indexes:
            %s
            
            Optimization candidates:
            %s
            
            Validation results:
            %s
            """;
}