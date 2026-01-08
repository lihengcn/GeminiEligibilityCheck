package com.google.gemini.dto;

import lombok.Data;
import java.util.List;

@Data
public class SheeridVerifyRequest {
    private List<String> verificationIds;
    /**
     * hCaptchaToken / API Key
     */
    private String apiKey;
    private boolean useLucky;
    private String programId;
}
