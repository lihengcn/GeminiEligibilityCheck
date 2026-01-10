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
import com.google.gemini.dto.VerifyHistoryRequest;
import com.google.gemini.dto.VerifyStatusRequest;
import com.google.gemini.dto.VerifyStatusesRequest;
import com.google.gemini.entity.Account;
import com.google.gemini.entity.AccountStatus;
import com.google.gemini.entity.VerifyHistory;
import com.google.gemini.entity.VerifyStatus;
import com.google.gemini.repository.VerifyHistoryRepository;
import com.google.gemini.repository.VerifyStatusRepository;
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
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class AccountController {
    private static final Logger log = LoggerFactory.getLogger(AccountController.class);

    private final AccountStorage accountStorage;
    private final TotpService totpService;
    private final SheeridVerifyService sheeridVerifyService;
    private final VerifyHistoryRepository verifyHistoryRepository;
    private final VerifyStatusRepository verifyStatusRepository;

    public AccountController(AccountStorage accountStorage, TotpService totpService, SheeridVerifyService sheeridVerifyService,
                             VerifyHistoryRepository verifyHistoryRepository, VerifyStatusRepository verifyStatusRepository) {
        this.accountStorage = accountStorage;
        this.totpService = totpService;
        this.sheeridVerifyService = sheeridVerifyService;
        this.verifyHistoryRepository = verifyHistoryRepository;
        this.verifyStatusRepository = verifyStatusRepository;
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
    public ResponseEntity<Map<String, Object>> onekeyStatus() {
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
            
            // 解析并计算统计
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> data = mapper.readValue(response.toString(), Map.class);
            List<Map<String, Object>> history = (List<Map<String, Object>>) data.get("resultHistory");
            
            int pass = 0, fail = 0, cancel = 0, other = 0;
            if (history != null) {
                for (Map<String, Object> item : history) {
                    Object r = item.get("r");
                    if (r == null) { other++; continue; }
                    int code = ((Number) r).intValue();
                    if (code == 0) pass++;
                    else if (code == 1) fail++;
                    else if (code == 2) cancel++;
                    else other++;
                }
            }
            int total = pass + fail + cancel;
            double rate = total > 0 ? (double) pass / total : 0;
            
            Map<String, Object> result = new HashMap<>();
            result.put("pass", pass);
            result.put("fail", fail);
            result.put("cancel", cancel);
            result.put("other", other);
            result.put("total", total);
            result.put("rate", rate);
            result.put("availableSlots", data.get("availableSlots"));
            result.put("maxConcurrent", data.get("maxConcurrent"));
            result.put("activeJobs", data.get("activeJobs"));
            
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    // ========== 验证历史 ==========
    @GetMapping("/verify-history")
    public ResponseEntity<Map<String, Object>> getVerifyHistory(@RequestParam(defaultValue = "200") int limit) {
        List<VerifyHistory> items = verifyHistoryRepository.findTop200ByOrderBySuccessAtDesc();
        Map<String, Object> result = new HashMap<>();
        result.put("items", items);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/verify-history")
    public ResponseEntity<String> addVerifyHistory(@RequestBody VerifyHistoryRequest request) {
        if (request == null || request.getEmail() == null || request.getEmail().isBlank()) {
            return ResponseEntity.badRequest().body("email required");
        }
        String email = request.getEmail().trim();
        if (!accountStorage.exists(email)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("account not found");
        }
        VerifyHistory history = new VerifyHistory();
        history.setEmail(email);
        verifyHistoryRepository.save(history);
        return ResponseEntity.ok("ok");
    }

    // ========== 验证状态 ==========
    @PostMapping("/verify-status")
    public ResponseEntity<String> upsertVerifyStatus(@RequestBody VerifyStatusRequest request) {
        if (request == null || request.getEmail() == null || request.getEmail().isBlank()) {
            return ResponseEntity.badRequest().body("email required");
        }
        String email = request.getEmail().trim();
        if (!accountStorage.exists(email)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("account not found");
        }
        VerifyStatus vs = verifyStatusRepository.findById(email).orElse(new VerifyStatus());
        vs.setEmail(email);
        vs.setStatus(request.getStatus() != null ? request.getStatus().trim() : "");
        vs.setMessage(request.getMessage() != null ? request.getMessage().trim() : "");
        verifyStatusRepository.save(vs);
        return ResponseEntity.ok("ok");
    }

    @PostMapping("/verify-statuses")
    public ResponseEntity<Map<String, Object>> getVerifyStatuses(@RequestBody VerifyStatusesRequest request) {
        List<String> emails = request != null && request.getEmails() != null ? request.getEmails() : Collections.emptyList();
        List<String> cleaned = emails.stream().filter(e -> e != null && !e.isBlank()).map(String::trim).collect(Collectors.toList());
        List<VerifyStatus> items = cleaned.isEmpty() ? Collections.emptyList() : verifyStatusRepository.findByEmailIn(cleaned);
        Map<String, Object> result = new HashMap<>();
        result.put("items", items);
        return ResponseEntity.ok(result);
    }

    // ========== 按状态查询账户 ==========
    @GetMapping("/accounts-by-status")
    public ResponseEntity<?> getAccountByStatus(@RequestParam String status) {
        if (status == null || status.isBlank()) {
            return ResponseEntity.badRequest().body("status required");
        }
        AccountStatus accountStatus;
        try {
            accountStatus = AccountStatus.valueOf(status.trim().toUpperCase());
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body("invalid status");
        }
        Account account = accountStorage.getAccountByStatus(accountStatus);
        if (account == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(account);
    }

    // ========== 删除已售账户 ==========
    @PostMapping("/delete-sold")
    public ResponseEntity<Map<String, Object>> deleteSoldAccounts() {
        int deleted = accountStorage.deleteSoldAccounts();
        Map<String, Object> result = new HashMap<>();
        result.put("deleted", deleted);
        return ResponseEntity.ok(result);
    }
}
