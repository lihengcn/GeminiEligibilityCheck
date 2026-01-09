package com.google.gemini.dto;

import lombok.Data;
import java.util.List;

@Data
public class SheeridVerifyRequest {
    private List<String> verificationIds;
    private String hCaptchaToken;
    private boolean useLucky;
    private String programId;
}
