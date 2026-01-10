package com.google.gemini.dto;

import lombok.Data;

@Data
public class VerifyStatusRequest {
    private String email;
    private String status;
    private String message;
}
