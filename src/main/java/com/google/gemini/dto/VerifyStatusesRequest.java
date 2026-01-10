package com.google.gemini.dto;

import lombok.Data;
import java.util.List;

@Data
public class VerifyStatusesRequest {
    private List<String> emails;
}
