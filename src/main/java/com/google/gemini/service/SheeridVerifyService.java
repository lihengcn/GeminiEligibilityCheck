package com.google.gemini.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gemini.dto.SheeridVerifyRequest;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Map;

@Service
public class SheeridVerifyService {
    private static final Logger log = LoggerFactory.getLogger(SheeridVerifyService.class);
    private final OkHttpClient httpClient = new OkHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${onekey.apiKey:}")
    private String onekeyApiKey;

    public String createCheckToken(SheeridVerifyRequest request) throws Exception {
        String csrfToken = fetchCsrfToken();
        if (csrfToken.isEmpty()) {
            throw new IllegalStateException("Failed to get CSRF token");
        }

        String hCaptchaToken = resolveHCaptchaToken(request);
        if (hCaptchaToken.isBlank()) {
            throw new IllegalStateException("Missing hCaptcha token");
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("verificationIds", request.getVerificationIds());
        payload.put("hCaptchaToken", hCaptchaToken);
        payload.put("useLucky", request.isUseLucky());
        if (request.getProgramId() != null && !request.getProgramId().isBlank()) {
            payload.put("programId", request.getProgramId().trim());
        }

        String jsonBody = objectMapper.writeValueAsString(payload);
        Request apiRequest = new Request.Builder()
                .url("https://batch.1key.me/api/batch")
                .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                .header("X-CSRF-Token", csrfToken)
                .header("Accept", "text/event-stream")
                .header("Origin", "https://batch.1key.me")
                .header("Referer", "https://batch.1key.me/")
                .header("User-Agent", "Mozilla/5.0")
                .build();

        try (Response apiResp = httpClient.newCall(apiRequest).execute()) {
            if (!apiResp.isSuccessful() || apiResp.body() == null) {
                throw new IllegalStateException("Upstream error: " + apiResp.code());
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(apiResp.body().byteStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    log.info("OneKey SSE data: {}", line);
                    if (trimmed.startsWith("data:")) {
                        String data = trimmed.substring(5).trim();
                        if (data.isEmpty()) {
                            continue;
                        }
                        Map<String, Object> parsed = objectMapper.readValue(data, Map.class);
                        Object token = parsed.get("checkToken");
                        if (token != null && !token.toString().isBlank()) {
                            return token.toString();
                        }
                    }
                }
            }
        }

        return "";
    }

    public String checkStatus(String checkToken) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("checkToken", checkToken);
        String jsonBody = objectMapper.writeValueAsString(payload);

        Request apiRequest = new Request.Builder()
                .url("https://batch.1key.me/api/check-status")
                .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                .header("Content-Type", "application/json")
                .header("Referer", "https://batch.1key.me/")
                .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36")
                .build();

        try (Response apiResp = httpClient.newCall(apiRequest).execute()) {
            if (!apiResp.isSuccessful() || apiResp.body() == null) {
                throw new IllegalStateException("Upstream error: " + apiResp.code());
            }
            return apiResp.body().string();
        }
    }

    private String fetchCsrfToken() throws Exception {
        Request pageRequest = new Request.Builder()
                .url("https://batch.1key.me/")
                .get()
                .header("User-Agent", "Mozilla/5.0")
                .build();

        try (Response pageResp = httpClient.newCall(pageRequest).execute()) {
            if (!pageResp.isSuccessful() || pageResp.body() == null) {
                return "";
            }
            String pageContent = pageResp.body().string();
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("window\\.CSRF_TOKEN\\s*=\\s*\"([^\"]+)\"")
                    .matcher(pageContent);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return "";
    }

    private String resolveHCaptchaToken(SheeridVerifyRequest request) {
        if (request.getHCaptchaToken() != null && !request.getHCaptchaToken().isBlank()) {
            return request.getHCaptchaToken().trim();
        }
        if (request.getApiKey() != null && !request.getApiKey().isBlank()) {
            return request.getApiKey().trim();
        }
        if (onekeyApiKey != null) {
            return onekeyApiKey.trim();
        }
        return "";
    }
}
