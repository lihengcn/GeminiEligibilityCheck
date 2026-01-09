package com.google.gemini.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateAccountRequest {
    private String originalEmail;
    private String email;
    private String password;
    private String recoveryEmail;
    private String authenticatorToken;
    private String status;
    private Boolean sold;
    private Boolean finished;
}
