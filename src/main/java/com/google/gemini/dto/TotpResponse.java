package com.google.gemini.dto;

public class TotpResponse {
    private String token;
    private int expires;

    public TotpResponse(String token, int expires) {
        this.token = token;
        this.expires = expires;
    }

    public String getToken() {
        return token;
    }

    public int getExpires() {
        return expires;
    }
}
