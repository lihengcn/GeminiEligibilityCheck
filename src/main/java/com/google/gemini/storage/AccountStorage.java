package com.google.gemini.storage;

import com.google.gemini.entity.Account;
import com.google.gemini.entity.AccountStatus;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class AccountStorage {
    private final Map<String, Account> accounts = new LinkedHashMap<>();

    @PostConstruct
    public void init() throws Exception {
        reloadFromFile();
    }

    public synchronized int reloadFromFile() throws Exception {
        // 重新加载时覆盖内存数据，保证顺序与文件一致。
        accounts.clear();
        ClassPathResource resource = new ClassPathResource("accounts.txt");
        if (!resource.exists()) {
            return 0;
        }
        int loaded = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                String[] parts = line.split(";", -1);
                if (parts.length < 6) {
                    continue;
                }
                String email = parts[0];
                if (email == null || email.isBlank()) {
                    continue;
                }
                // 按文件顺序加载账号，默认标记为空闲。
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
                loaded++;
            }
        }
        return loaded;
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
}
