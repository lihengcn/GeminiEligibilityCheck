package com.google.gemini.dto;

import lombok.Data;

@Data
public class UpdateSoldRequest {
    private String email;
    private Boolean sold;
}

