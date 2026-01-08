package com.google.gemini.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gemini.dto.SheeridVerifyRequest;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Service
public class SheeridVerifyService {
    private final OkHttpClient httpClient = new OkHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${onekey.apiKey:}")
    private String onekeyApiKey;

    @Async
    public void verify(SseEmitter emitter, SheeridVerifyRequest request) {
        try {
            Request pageRequest = new Request.Builder()
                    .url("https://batch.1key.me/")
                    .get()
                    .header("User-Agent", "Mozilla/5.0")
                    .build();

            String pageContent;
            try (Response pageResp = httpClient.newCall(pageRequest).execute()) {
                if (!pageResp.isSuccessful() || pageResp.body() == null) {
                    emitter.send("{\"error\":\"Failed to load CSRF page\"}");
                    emitter.complete();
                    return;
                }
                pageContent = pageResp.body().string();
            }

            String csrfToken = "";
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("window\\.CSRF_TOKEN\\s*=\\s*\"([^\"]+)\"")
                    .matcher(pageContent);
            if (matcher.find()) {
                csrfToken = matcher.group(1);
            }

            if (csrfToken.isEmpty()) {
                emitter.send("{\"error\":\"Failed to get CSRF token\"}");
                emitter.complete();
                return;
            }

            String hCaptchaToken = onekeyApiKey;

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
                    .header("Origin", "https://batch.1key.me")
                    .header("Referer", "https://batch.1key.me/")
                    .header("User-Agent", "Mozilla/5.0")
                    .build();

            try (Response apiResp = httpClient.newCall(apiRequest).execute()) {
                if (!apiResp.isSuccessful() || apiResp.body() == null) {
                    emitter.send("{\"error\":\"Upstream error: " + apiResp.code() + "\"}");
                    emitter.complete();
                    return;
                }
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(apiResp.body().byteStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String trimmed = line.trim();
                        if (trimmed.isEmpty()) {
                            continue;
                        }
                        if (trimmed.startsWith("data:")) {
                            String data = trimmed.substring(5).trim();
                            if (!data.isEmpty()) {
                                emitter.send(data);
                            }
                        }
                    }
                }
            }
            emitter.complete();
        } catch (Exception e) {
            try {
                emitter.send("{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}");
            } catch (Exception ignored) {
            } finally {
                emitter.completeWithError(e);
            }
        }
    }
}
