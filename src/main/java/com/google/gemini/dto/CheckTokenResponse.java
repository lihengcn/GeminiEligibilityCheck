package com.google.gemini.dto;

import lombok.Data;

@Data
public class CheckTokenResponse {
    private String checkToken;

    public CheckTokenResponse(String checkToken) {
        this.checkToken = checkToken;
    }
}
