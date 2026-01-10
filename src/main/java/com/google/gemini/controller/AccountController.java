package com.google.gemini.controller;

import com.google.gemini.dto.CallbackRequest;
import com.google.gemini.dto.CheckStatusRequest;
import com.google.gemini.dto.CheckTokenResponse;
import com.google.gemini.dto.ImportRequest;
import com.google.gemini.dto.ImportResponse;
import com.google.gemini.dto.RestoreStatusesRequest;
import com.google.gemini.dto.StatusView;
import com.google.gemini.dto.TotpRequest;
import com.google.gemini.dto.TotpResponse;
import com.google.gemini.dto.DeleteAccountsRequest;
import com.google.gemini.dto.DeleteAccountRequest;
import com.google.gemini.dto.UpdateFinishedRequest;
import com.google.gemini.dto.UpdateSoldRequest;
import com.google.gemini.dto.UpdateSheeridRequest;
import com.google.gemini.dto.UpdateStatusRequest;
import com.google.gemini.dto.StorageInfoResponse;
import com.google.gemini.dto.SheeridVerifyRequest;
import com.google.gemini.dto.UpdateAccountRequest;
import com.google.gemini.entity.Account;
import com.google.gemini.entity.AccountStatus;
import com.google.gemini.service.SheeridVerifyService;
import com.google.gemini.service.TotpService;
import com.google.gemini.storage.AccountStorage;
import com.google.gemini.storage.AccountStorage.ImportResult;
import com.google.gemini.storage.AccountStorage.ImportTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api")
public class AccountController {
    private static final Logger log = LoggerFactory.getLogger(AccountController.class);

    /**
     * 账号池与并发控制
     */
    private final AccountStorage accountStorage;
    private final TotpService totpService;
    private final SheeridVerifyService sheeridVerifyService;

    public AccountController(AccountStorage accountStorage, TotpService totpService, SheeridVerifyService sheeridVerifyService) {
        this.accountStorage = accountStorage;
        this.totpService = totpService;
        this.sheeridVerifyService = sheeridVerifyService;
    }

    /**
     * 拉取一个空闲账号并置为检查中。
     */
    @GetMapping("/poll")
    public ResponseEntity<Account> poll() {
        Account account = accountStorage.pollAccount();
        if (account == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(account);
    }

    /**
     * 回调只接受 QUALIFIED 或 INVALID。
     */
    @PostMapping("/callback")
    public ResponseEntity<String> callback(@RequestBody CallbackRequest request) throws Exception {
        String email = request == null ? null : request.getEmail();
        String result = request == null ? null : request.getResult();
        String sheeridUrl = request == null ? null : request.getSheeridUrl();
        log.info("callback request: email={}, result={}, sheeridUrl={}", email, result, sheeridUrl);

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

    /**
     * 防止无回调导致卡死，重置检查中状态。
     */
    @PostMapping("/reset-checking")
    public ResponseEntity<String> resetChecking() {
        int reset = accountStorage.resetCheckingToIdle();
        return ResponseEntity.ok("reset:" + reset);
    }

    @PostMapping("/import")
    public ResponseEntity<ImportResponse> importAccounts(@RequestBody ImportRequest request) {
        if (request == null || request.getContent() == null || request.getContent().isBlank()) {
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
        ImportResult result = accountStorage.importFromText(request.getContent(), template);
        return ResponseEntity.ok(new ImportResponse(result.added(), result.updated(), result.skipped()));
    }

    @GetMapping("/accounts")
    public ResponseEntity<StatusView> accounts() {
        return ResponseEntity.ok(StatusView.from(accountStorage.listAccounts()));
    }

    @GetMapping("/info")
    public ResponseEntity<StorageInfoResponse> info() {
        String storage = accountStorage.isPostgresEnabled() ? "postgres" : "sqlite";
        return ResponseEntity.ok(new StorageInfoResponse(storage));
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

    @PostMapping("/delete-batch")
    public ResponseEntity<String> deleteAccounts(@RequestBody DeleteAccountsRequest request) {
        if (request == null || request.getEmails() == null || request.getEmails().isEmpty()) {
            return ResponseEntity.badRequest().body("emails required");
        }
        int deleted = accountStorage.deleteAccounts(request.getEmails());
        return ResponseEntity.ok("deleted:" + deleted);
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

    @PostMapping("/update-account")
    public ResponseEntity<String> updateAccount(@RequestBody UpdateAccountRequest request) {
        if (request == null || request.getOriginalEmail() == null || request.getOriginalEmail().isBlank()
                || request.getEmail() == null || request.getEmail().isBlank()) {
            return ResponseEntity.badRequest().body("email/originalEmail required");
        }
        AccountStatus status = null;
        if (request.getStatus() != null && !request.getStatus().isBlank()) {
            try {
                status = AccountStatus.valueOf(request.getStatus().trim().toUpperCase());
            } catch (Exception ex) {
                return ResponseEntity.badRequest().body("invalid status");
            }
        }
        AccountStorage.UpdateResult result = accountStorage.updateAccount(request, status);
        if (result == AccountStorage.UpdateResult.NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("account not found");
        }
        if (result == AccountStorage.UpdateResult.EMAIL_CONFLICT) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("email exists");
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

    @PostMapping(value = "/sheerid-verify", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CheckTokenResponse> sheeridVerify(@RequestBody SheeridVerifyRequest request) {
        if (request == null || request.getVerificationIds() == null || request.getVerificationIds().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        if (request.getVerificationIds().size() > 5) {
            return ResponseEntity.badRequest().build();
        }
        try {
            String checkToken = sheeridVerifyService.createCheckToken(request);
            if (checkToken == null || checkToken.isBlank()) {
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
            }
            return ResponseEntity.ok(new CheckTokenResponse(checkToken));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
    }

    @PostMapping(value = "/check-status", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> checkStatus(@RequestBody CheckStatusRequest request) {
        if (request == null || request.getCheckToken() == null || request.getCheckToken().isBlank()) {
            return ResponseEntity.badRequest().body("{\"error\":\"checkToken required\"}");
        }
        try {
            String result = sheeridVerifyService.checkStatus(request.getCheckToken().trim());
            return ResponseEntity.ok(result);
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body("{\"error\":\"upstream failed\"}");
        }
    }

    @GetMapping("/onekey-status")
    public ResponseEntity<String> onekeyStatus() {
        try {
            // 先获取 CSRF token
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

            // 调用 status API
            URL apiUrl = new URL("https://batch.1key.me/api/status");
            HttpURLConnection conn = (HttpURLConnection) apiUrl.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("X-CSRF-Token", csrfToken);
            conn.setRequestProperty("Origin", "https://batch.1key.me");
            conn.setRequestProperty("Referer", "https://batch.1key.me/");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            
            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }
            
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(response.toString());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}");
        }
    }
}
