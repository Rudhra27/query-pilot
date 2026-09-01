package com.rudhra.querypilot.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class QueryRequest {

    @NotBlank(message = "SQL statement cannot be empty")
    private String sql;
}
