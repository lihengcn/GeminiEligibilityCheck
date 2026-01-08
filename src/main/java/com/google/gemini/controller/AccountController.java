package com.google.gemini.controller;

import com.google.gemini.dto.CallbackRequest;
import com.google.gemini.dto.ImportRequest;
import com.google.gemini.dto.ImportResponse;
import com.google.gemini.dto.RestoreStatusesRequest;
import com.google.gemini.dto.StatusView;
import com.google.gemini.dto.TotpRequest;
import com.google.gemini.dto.TotpResponse;
import com.google.gemini.dto.DeleteAccountRequest;
import com.google.gemini.dto.UpdateFinishedRequest;
import com.google.gemini.dto.UpdateSoldRequest;
import com.google.gemini.dto.UpdateSheeridRequest;
import com.google.gemini.dto.UpdateStatusRequest;
import com.google.gemini.dto.StorageInfoResponse;
import com.google.gemini.dto.SheeridVerifyRequest;
import com.google.gemini.entity.Account;
import com.google.gemini.entity.AccountStatus;
import com.google.gemini.service.TotpService;
import com.google.gemini.storage.AccountStorage;
import com.google.gemini.storage.AccountStorage.ImportMode;
import com.google.gemini.storage.AccountStorage.ImportResult;
import com.google.gemini.storage.AccountStorage.ImportTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api")
public class AccountController {
    // 账号池与并发控制
    private final AccountStorage accountStorage;
    private final TotpService totpService;
    
    @org.springframework.beans.factory.annotation.Value("${onekey.apiKey:}")
    private String onekeyApiKey;

    public AccountController(AccountStorage accountStorage, TotpService totpService) {
        this.accountStorage = accountStorage;
        this.totpService = totpService;
    }

    @GetMapping("/poll")
    public ResponseEntity<Account> poll() {
        // 拉取一个空闲账号并置为检查中。
        Account account = accountStorage.pollAccount();
        if (account == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(account);
    }

    @PostMapping("/callback")
    public ResponseEntity<String> callback(@RequestBody CallbackRequest request) throws Exception {
        // 回调只接受 QUALIFIED 或 INVALID。
        if (request == null || request.getEmail() == null || request.getEmail().isBlank()
                || request.getResult() == null || request.getResult().isBlank()) {
            return ResponseEntity.badRequest().body("email/result required");
        }

        AccountStatus status;
        try {
            status = AccountStatus.valueOf(request.getResult().trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body("result must be QUALIFIED or INVALID");
        }

        if (status != AccountStatus.QUALIFIED && status != AccountStatus.INVALID) {
            return ResponseEntity.badRequest().body("result must be QUALIFIED or INVALID");
        }

        Account account = accountStorage.updateStatus(request.getEmail().trim(), status);
        if (account == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("account not found");
        }

        if (status == AccountStatus.QUALIFIED && request.getSheeridUrl() != null && !request.getSheeridUrl().isBlank()) {
            String url = request.getSheeridUrl().trim();
            if (url.length() <= 2048) {
                accountStorage.upsertSheeridUrl(request.getEmail().trim(), url);
            }
        }

        return ResponseEntity.ok("ok");
    }

    @PostMapping("/sheerid")
    public ResponseEntity<String> updateSheerid(@RequestBody UpdateSheeridRequest request) {
        if (request == null || request.getEmail() == null || request.getEmail().isBlank()) {
            return ResponseEntity.badRequest().body("email required");
        }
        String email = request.getEmail().trim();
        String url = request.getSheeridUrl() == null ? "" : request.getSheeridUrl().trim();
        if (url.length() > 2048) {
            return ResponseEntity.badRequest().body("sheeridUrl too long");
        }
        if (!accountStorage.exists(email)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("account not found");
        }
        accountStorage.upsertSheeridUrl(email, url);
        return ResponseEntity.ok("ok");
    }

    @PostMapping("/2fa")
    public ResponseEntity<TotpResponse> totp(@RequestBody TotpRequest request) {
        if (request == null || request.getSecret() == null || request.getSecret().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            TotpService.TotpResult result = totpService.generateToken(request.getSecret().trim());
            return ResponseEntity.ok(new TotpResponse(result.getCode(), result.getSecondsRemaining()));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
    }

    @PostMapping("/reset-checking")
    public ResponseEntity<String> resetChecking() {
        // 防止无回调导致卡死，重置检查中状态。
        int reset = accountStorage.resetCheckingToIdle();
        return ResponseEntity.ok("reset:" + reset);
    }

    @PostMapping("/import")
    public ResponseEntity<ImportResponse> importAccounts(@RequestBody ImportRequest request) {
        if (request == null || request.getContent() == null || request.getContent().isBlank()
                || request.getMode() == null || request.getMode().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        ImportMode mode;
        try {
            mode = ImportMode.valueOf(request.getMode().trim().toUpperCase());
        } catch (Exception ex) {
            return ResponseEntity.badRequest().build();
        }
        ImportTemplate template = ImportTemplate.AUTO;
        if (request.getTemplate() != null && !request.getTemplate().isBlank()) {
            try {
                template = ImportTemplate.valueOf(request.getTemplate().trim().toUpperCase());
            } catch (Exception ignored) {
                template = ImportTemplate.AUTO;
            }
        }
        ImportResult result = accountStorage.importFromText(request.getContent(), mode, template);
        return ResponseEntity.ok(new ImportResponse(result.added(), result.updated(), result.skipped()));
    }

    @GetMapping("/accounts")
    public ResponseEntity<StatusView> accounts() {
        return ResponseEntity.ok(StatusView.from(accountStorage.listAccounts()));
    }

    @GetMapping("/info")
    public ResponseEntity<StorageInfoResponse> info() {
        String storage = accountStorage.isPostgresEnabled() ? "postgres" : "file";
        return ResponseEntity.ok(new StorageInfoResponse(storage, accountStorage.isPgAllowOverwrite()));
    }

    @PostMapping("/sold")
    public ResponseEntity<String> updateSold(@RequestBody UpdateSoldRequest request) {
        if (request == null || request.getEmail() == null || request.getEmail().isBlank() || request.getSold() == null) {
            return ResponseEntity.badRequest().body("email/sold required");
        }
        boolean ok = accountStorage.updateSold(request.getEmail().trim(), request.getSold());
        if (!ok) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("account not found");
        }
        return ResponseEntity.ok("ok");
    }

    @PostMapping("/finished")
    public ResponseEntity<String> updateFinished(@RequestBody UpdateFinishedRequest request) {
        if (request == null || request.getEmail() == null || request.getEmail().isBlank() || request.getFinished() == null) {
            return ResponseEntity.badRequest().body("email/finished required");
        }
        boolean ok = accountStorage.updateFinished(request.getEmail().trim(), request.getFinished());
        if (!ok) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("account not found");
        }
        return ResponseEntity.ok("ok");
    }

    @PostMapping("/delete")
    public ResponseEntity<String> deleteAccount(@RequestBody DeleteAccountRequest request) {
        if (request == null || request.getEmail() == null || request.getEmail().isBlank()) {
            return ResponseEntity.badRequest().body("email required");
        }
        boolean ok = accountStorage.deleteAccount(request.getEmail().trim());
        if (!ok) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("account not found");
        }
        return ResponseEntity.ok("ok");
    }

    @PostMapping("/status")
    public ResponseEntity<String> updateStatusManual(@RequestBody UpdateStatusRequest request) {
        if (request == null || request.getEmail() == null || request.getEmail().isBlank()
                || request.getStatus() == null || request.getStatus().isBlank()) {
            return ResponseEntity.badRequest().body("email/status required");
        }
        AccountStatus status;
        try {
            status = AccountStatus.valueOf(request.getStatus().trim().toUpperCase());
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body("invalid status");
        }
        Account account = accountStorage.updateStatus(request.getEmail().trim(), status);
        if (account == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("account not found");
        }
        return ResponseEntity.ok("ok");
    }

    @PostMapping("/restore-statuses")
    public ResponseEntity<String> restoreStatuses(@RequestBody RestoreStatusesRequest request) {
        if (request == null || request.getItems() == null || request.getItems().isEmpty()) {
            return ResponseEntity.badRequest().body("items required");
        }
        int updated = 0;
        int skipped = 0;
        for (RestoreStatusesRequest.Item item : request.getItems()) {
            if (item == null || item.getEmail() == null || item.getEmail().isBlank()
                    || item.getStatus() == null || item.getStatus().isBlank()) {
                skipped++;
                continue;
            }
            AccountStatus status;
            try {
                status = AccountStatus.valueOf(item.getStatus().trim().toUpperCase());
            } catch (Exception ex) {
                skipped++;
                continue;
            }
            if (accountStorage.restoreStatus(item.getEmail().trim(), status)) {
                updated++;
            } else {
                skipped++;
            }
        }
        return ResponseEntity.ok("updated:" + updated + ",skipped:" + skipped);
    }

    @PostMapping(value = "/sheerid-verify", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> sheeridVerify(@RequestBody SheeridVerifyRequest request) {
        if (request == null || request.getVerificationIds() == null || request.getVerificationIds().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        if (request.getVerificationIds().size() > 5) {
            return ResponseEntity.badRequest().build();
        }

        StreamingResponseBody stream = outputStream -> {
            HttpURLConnection conn = null;
            try {
                // 1. 获取 CSRF token
                URL pageUrl = new URL("https://batch.1key.me/");
                HttpURLConnection pageConn = (HttpURLConnection) pageUrl.openConnection();
                pageConn.setRequestMethod("GET");
                pageConn.setRequestProperty("User-Agent", "Mozilla/5.0");
                
                StringBuilder pageContent = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(pageConn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        pageContent.append(line);
                    }
                }
                
                String csrfToken = "";
                java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("window\\.CSRF_TOKEN\\s*=\\s*\"([^\"]+)\"").matcher(pageContent.toString());
                if (matcher.find()) {
                    csrfToken = matcher.group(1);
                }
                
                if (csrfToken.isEmpty()) {
                    outputStream.write("data: {\"error\":\"Failed to get CSRF token\"}\n\n".getBytes(StandardCharsets.UTF_8));
                    outputStream.flush();
                    return;
                }

                // 2. 调用 batch API
                URL apiUrl = new URL("https://batch.1key.me/api/batch");
                conn = (HttpURLConnection) apiUrl.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("X-CSRF-Token", csrfToken);
                conn.setRequestProperty("Origin", "https://batch.1key.me");
                conn.setRequestProperty("Referer", "https://batch.1key.me/");
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                conn.setDoOutput(true);

                String jsonBody = String.format(
                    "{\"verificationIds\":%s,\"hCaptchaToken\":\"%s\",\"useLucky\":%s,\"programId\":\"%s\"}",
                    new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(request.getVerificationIds()),
                    // 优先用后端配置的 API Key，否则用前端传的
                    (onekeyApiKey != null && !onekeyApiKey.isBlank()) ? onekeyApiKey : (request.getApiKey() != null ? request.getApiKey() : ""),
                    request.isUseLucky(),
                    request.getProgramId() != null ? request.getProgramId() : ""
                );

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
                }

                // 3. 流式转发响应
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        outputStream.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                        outputStream.flush();
                    }
                }
            } catch (Exception e) {
                try {
                    outputStream.write(("data: {\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}\n\n").getBytes(StandardCharsets.UTF_8));
                    outputStream.flush();
                } catch (Exception ignored) {}
            } finally {
                if (conn != null) conn.disconnect();
            }
        };

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(stream);
    }
}
