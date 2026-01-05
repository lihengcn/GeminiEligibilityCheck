package com.google.gemini.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ImportResponse {
    private int added;
    private int updated;
    private int skipped;
}
