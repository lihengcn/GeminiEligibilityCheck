package com.google.gemini.dto;

import lombok.Data;

@Data
public class ImportRequest {
    private String content;
    private String template;
}
