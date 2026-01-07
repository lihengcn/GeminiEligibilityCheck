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
import com.google.gemini.dto.UpdateStatusRequest;
import com.google.gemini.dto.StorageInfoResponse;
import com.google.gemini.entity.Account;
import com.google.gemini.entity.AccountStatus;
import com.google.gemini.service.TotpService;
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
    private final TotpService totpService;

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
        ImportResult result = accountStorage.importFromText(request.getContent(), mode);
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
}
