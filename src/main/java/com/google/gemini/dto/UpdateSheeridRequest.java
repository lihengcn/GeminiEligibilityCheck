package com.google.gemini.dto;

import lombok.Data;

@Data
public class UpdateSheeridRequest {
    private String email;
    private String sheeridUrl;
}
