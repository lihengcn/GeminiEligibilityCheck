package com.google.gemini.controller;

import com.google.gemini.dto.CallbackRequest;
import com.google.gemini.dto.ImportRequest;
import com.google.gemini.dto.ImportResponse;
import com.google.gemini.dto.StatusView;
import com.google.gemini.entity.Account;
import com.google.gemini.entity.AccountStatus;
import com.google.gemini.service.ResultExportService;
import com.google.gemini.storage.AccountStorage;
import com.google.gemini.storage.AccountStorage.ImportMode;
import com.google.gemini.storage.AccountStorage.ImportResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AccountController {
    // 账号池与并发控制
    private final AccountStorage accountStorage;
    // 结果持久化服务
    private final ResultExportService resultExportService;

    public AccountController(AccountStorage accountStorage, ResultExportService resultExportService) {
        this.accountStorage = accountStorage;
        this.resultExportService = resultExportService;
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

        resultExportService.appendResult(account);
        return ResponseEntity.ok("ok");
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
        ImportResult result = accountStorage.importFromText(request.getContent(), mode);
        return ResponseEntity.ok(new ImportResponse(result.added(), result.updated(), result.skipped()));
    }

    @GetMapping("/accounts")
    public ResponseEntity<StatusView> accounts() {
        return ResponseEntity.ok(StatusView.from(accountStorage.listAccounts()));
    }
}
