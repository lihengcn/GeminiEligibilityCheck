package com.google.gemini.storage;

import com.google.gemini.entity.Account;
import com.google.gemini.entity.AccountStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AccountStorage {
    private final Map<String, Account> accounts = new LinkedHashMap<>();

    public synchronized ImportResult importFromText(String content, ImportMode mode) {
        if (mode == ImportMode.OVERWRITE) {
            accounts.clear();
        }
        int added = 0;
        int updated = 0;
        int skipped = 0;
        String[] lines = content.split("\\r?\\n");
        for (String rawLine : lines) {
            if (rawLine == null) {
                skipped++;
                continue;
            }
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split(";", -1);
            if (parts.length < 6) {
                skipped++;
                continue;
            }
            String email = parts[0];
            if (email == null || email.isBlank()) {
                skipped++;
                continue;
            }
            Account existing = accounts.get(email);
            if (existing != null) {
                // 追加模式下保留原状态，仅更新账号资料。
                existing.setPassword(parts[1]);
                existing.setAuthenticatorToken(parts[2]);
                existing.setAppPassword(parts[3]);
                existing.setAuthenticatorUrl(parts[4]);
                existing.setMessagesUrl(parts[5]);
                updated++;
                continue;
            }
            Account account = new Account(
                    email,
                    parts[1],
                    parts[2],
                    parts[3],
                    parts[4],
                    parts[5],
                    AccountStatus.IDLE
            );
            accounts.put(email, account);
            added++;
        }
        return new ImportResult(added, updated, skipped);
    }

    public synchronized Account pollAccount() {
        // 同步锁保证并发下不会分配到同一个账号。
        for (Account account : accounts.values()) {
            if (account.getStatus() == AccountStatus.IDLE) {
                account.setStatus(AccountStatus.CHECKING);
                return account;
            }
        }
        return null;
    }

    public synchronized Account updateStatus(String email, AccountStatus status) {
        Account account = accounts.get(email);
        if (account == null) {
            return null;
        }
        // 仅在内存中更新状态。
        account.setStatus(status);
        return account;
    }

    public synchronized int resetCheckingToIdle() {
        // 将所有检查中的账号重置为空闲。
        int reset = 0;
        for (Account account : accounts.values()) {
            if (account.getStatus() == AccountStatus.CHECKING) {
                account.setStatus(AccountStatus.IDLE);
                reset++;
            }
        }
        return reset;
    }

    public synchronized List<Account> listAccounts() {
        return new ArrayList<>(accounts.values());
    }

    public record ImportResult(int added, int updated, int skipped) {
    }

    public enum ImportMode {
        OVERWRITE,
        APPEND
    }
}
