package com.google.gemini.dto;

import lombok.Data;

@Data
public class UpdateStatusRequest {
    private String email;
    private String status;
}

