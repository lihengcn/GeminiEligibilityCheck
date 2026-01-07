package com.google.gemini.dto;

import lombok.Data;

@Data
public class UpdateFinishedRequest {
    private String email;
    private Boolean finished;
}

