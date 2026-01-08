package com.google.gemini.storage;

import com.google.gemini.entity.Account;
import com.google.gemini.entity.AccountStatus;
import com.google.gemini.entity.SheeridLink;
import com.google.gemini.repository.AccountRepository;
import com.google.gemini.repository.SheeridLinkRepository;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AccountStorage {
    private static final Logger log = LoggerFactory.getLogger(AccountStorage.class);

    private final AccountRepository accountRepository;
    private final SheeridLinkRepository sheeridLinkRepository;
    private final EntityManager entityManager;
    private final boolean pgAllowOverwrite;

    public AccountStorage(
            AccountRepository accountRepository,
            SheeridLinkRepository sheeridLinkRepository,
            EntityManager entityManager,
            @Value("${gemini.pg.allowOverwrite:false}") boolean pgAllowOverwrite
    ) {
        this.accountRepository = accountRepository;
        this.sheeridLinkRepository = sheeridLinkRepository;
        this.entityManager = entityManager;
        this.pgAllowOverwrite = pgAllowOverwrite;
    }

    private static boolean looksLikeHeaderEmail(String email) {
        if (email == null) {
            return true;
        }
        String trimmed = email.trim();
        if (trimmed.isEmpty()) {
            return true;
        }
        String lower = trimmed.toLowerCase();
        if (lower.equals("email") || lower.equals("login") || lower.equals("account")) {
            return true;
        }
        return trimmed.contains("{") || trimmed.contains("}");
    }

    private static boolean looksLikeRecoveryEmail(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        int at = trimmed.indexOf('@');
        int dot = trimmed.lastIndexOf('.');
        return at > 0 && dot > at + 1 && dot < trimmed.length() - 1;
    }

    private static ImportTemplate resolveTemplate(ImportTemplate template, String[] parts) {
        if (template == null || template == ImportTemplate.AUTO) {
            if (parts.length == 3 && looksLikeRecoveryEmail(parts[2])) {
                return ImportTemplate.RECOVERY_EMAIL;
            }
            return ImportTemplate.TOKEN;
        }
        return template;
    }

    private static class ParsedImportLine {
        private String email;
        private String password;
        private String recoveryEmail;
        private String authenticatorToken;
        private String appPassword;
        private String authenticatorUrl;
        private String messagesUrl;
        private boolean hasPassword;
        private boolean hasRecoveryEmail;
        private boolean hasAuthenticatorToken;
        private boolean hasAppPassword;
        private boolean hasAuthenticatorUrl;
        private boolean hasMessagesUrl;
        private ImportTemplate templateUsed;
    }

    private ParsedImportLine parseImportLine(String line, ImportTemplate template) {
        String[] parts = line.split(";", -1);
        if (parts.length < 3) {
            return null;
        }
        String email = parts[0].trim();
        if (looksLikeHeaderEmail(email)) {
            return null;
        }
        ImportTemplate effectiveTemplate = resolveTemplate(template, parts);
        ParsedImportLine parsed = new ParsedImportLine();
        parsed.email = email;
        parsed.password = parts.length > 1 ? parts[1] : "";
        parsed.hasPassword = parts.length > 1;
        parsed.templateUsed = effectiveTemplate;
        if (effectiveTemplate == ImportTemplate.RECOVERY_EMAIL) {
            parsed.recoveryEmail = parts.length > 2 ? parts[2] : "";
            parsed.hasRecoveryEmail = parts.length > 2;
            return parsed;
        }
        parsed.authenticatorToken = parts.length > 2 ? parts[2] : "";
        parsed.hasAuthenticatorToken = parts.length > 2;
        parsed.appPassword = parts.length > 3 ? parts[3] : "";
        parsed.hasAppPassword = parts.length > 3;
        parsed.authenticatorUrl = parts.length > 4 ? parts[4] : "";
        parsed.hasAuthenticatorUrl = parts.length > 4;
        parsed.messagesUrl = parts.length > 5 ? parts[5] : "";
        parsed.hasMessagesUrl = parts.length > 5;
        return parsed;
    }

    public ImportResult importFromText(String content, ImportMode mode) {
        return importFromText(content, mode, ImportTemplate.AUTO);
    }

    @Transactional
    public ImportResult importFromText(String content, ImportMode mode, ImportTemplate template) {
        if (mode == ImportMode.OVERWRITE && !pgAllowOverwrite) {
            mode = ImportMode.APPEND;
        }
        int added = 0;
        int updated = 0;
        int skipped = 0;
        if (mode == ImportMode.OVERWRITE) {
            sheeridLinkRepository.deleteAll();
            accountRepository.deleteAll();
        }
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
            ParsedImportLine parsed = parseImportLine(line, template);
            if (parsed == null) {
                skipped++;
                continue;
            }
            try {
                if (importSingleLine(parsed, mode)) {
                    added++;
                } else {
                    updated++;
                }
            } catch (Exception ex) {
                skipped++;
                log.warn("JPA import failed for {}: {}", parsed.email, ex.getMessage());
            }
        }
        return new ImportResult(added, updated, skipped);
    }

    private boolean importSingleLine(ParsedImportLine parsed, ImportMode mode) {
        String email = parsed.email;
        Account existing = accountRepository.findById(email).orElse(null);
        if (existing != null && mode == ImportMode.APPEND) {
            applyImportUpdate(existing, parsed);
            accountRepository.save(existing);
            return false;
        }
        Account account = new Account();
        account.setEmail(email);
        account.setPassword(parsed.hasPassword ? parsed.password : "");
        account.setRecoveryEmail(parsed.hasRecoveryEmail ? parsed.recoveryEmail : "");
        account.setAuthenticatorToken(parsed.hasAuthenticatorToken ? parsed.authenticatorToken : "");
        account.setAppPassword(parsed.hasAppPassword ? parsed.appPassword : "");
        account.setAuthenticatorUrl(parsed.hasAuthenticatorUrl ? parsed.authenticatorUrl : "");
        account.setMessagesUrl(parsed.hasMessagesUrl ? parsed.messagesUrl : "");
        account.setSold(false);
        account.setFinished(false);
        account.setStatus(AccountStatus.IDLE);
        accountRepository.save(account);
        return true;
    }

    private void applyImportUpdate(Account account, ParsedImportLine parsed) {
        if (parsed.hasPassword) {
            account.setPassword(parsed.password);
        }
        if (parsed.templateUsed == ImportTemplate.RECOVERY_EMAIL) {
            if (parsed.hasRecoveryEmail) {
                account.setRecoveryEmail(parsed.recoveryEmail);
            }
            return;
        }
        if (parsed.hasAuthenticatorToken) {
            account.setAuthenticatorToken(parsed.authenticatorToken);
        }
        if (parsed.hasAppPassword) {
            account.setAppPassword(parsed.appPassword);
        }
        if (parsed.hasAuthenticatorUrl) {
            account.setAuthenticatorUrl(parsed.authenticatorUrl);
        }
        if (parsed.hasMessagesUrl) {
            account.setMessagesUrl(parsed.messagesUrl);
        }
    }

    @Transactional
    public Account pollAccount() {
        String email = pollAccountEmail();
        if (email == null) {
            return null;
        }
        Account account = accountRepository.findById(email).orElse(null);
        if (account == null) {
            return null;
        }
        account.setStatus(AccountStatus.CHECKING);
        accountRepository.save(account);
        attachSheerid(account);
        return account;
    }

    private String pollAccountEmail() {
        String sql = """
                SELECT email
                FROM gem_accounts
                WHERE sold = FALSE AND status = 'IDLE'
                ORDER BY email
                LIMIT 1
                FOR UPDATE SKIP LOCKED
                """;
        @SuppressWarnings("unchecked")
        List<Object> result = entityManager.createNativeQuery(sql).getResultList();
        if (result.isEmpty()) {
            return null;
        }
        return result.get(0).toString();
    }

    @Transactional
    public Account updateStatus(String email, AccountStatus status) {
        if (email == null || email.isBlank() || status == null) {
            return null;
        }
        String trimmed = email.trim();
        Account account = accountRepository.findById(trimmed).orElse(null);
        if (account == null) {
            return null;
        }
        account.setStatus(status);
        accountRepository.save(account);
        attachSheerid(account);
        return account;
    }

    @Transactional
    public int resetCheckingToIdle() {
        return accountRepository.resetCheckingToIdle();
    }

    @Transactional
    public boolean restoreStatus(String email, AccountStatus status) {
        if (email == null || email.isBlank() || status == null) {
            return false;
        }
        String trimmed = email.trim();
        Account account = accountRepository.findById(trimmed).orElse(null);
        if (account == null) {
            return false;
        }
        account.setStatus(status);
        accountRepository.save(account);
        return true;
    }

    @Transactional
    public boolean updateSold(String email, boolean sold) {
        if (email == null || email.isBlank()) {
            return false;
        }
        String trimmed = email.trim();
        return accountRepository.updateSoldByEmail(trimmed, sold) > 0;
    }

    @Transactional
    public boolean updateFinished(String email, boolean finished) {
        if (email == null || email.isBlank()) {
            return false;
        }
        String trimmed = email.trim();
        Account account = accountRepository.findById(trimmed).orElse(null);
        if (account == null) {
            return false;
        }
        if (finished) {
            if (account.getStatus() != AccountStatus.PRODUCT) {
                account.setStatus(AccountStatus.PRODUCT);
            }
        } else if (account.getStatus() == AccountStatus.PRODUCT) {
            account.setStatus(AccountStatus.QUALIFIED);
        }
        accountRepository.save(account);
        return true;
    }

    @Transactional
    public boolean deleteAccount(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        String trimmed = email.trim();
        if (!accountRepository.existsById(trimmed)) {
            return false;
        }
        accountRepository.deleteById(trimmed);
        return true;
    }

    @Transactional(readOnly = true)
    public List<Account> listAccounts() {
        List<Account> list = accountRepository.findAll(Sort.by(Sort.Direction.ASC, "email"));
        attachSheerid(list);
        return list;
    }

    @Transactional(readOnly = true)
    public boolean exists(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        return accountRepository.existsById(email.trim());
    }

    @Transactional
    public void upsertSheeridUrl(String email, String sheeridUrl) {
        if (email == null || email.isBlank()) {
            return;
        }
        String trimmedEmail = email.trim();
        String url = sheeridUrl == null ? "" : sheeridUrl.trim();
        if (url.isEmpty()) {
            sheeridLinkRepository.deleteById(trimmedEmail);
            return;
        }
        sheeridLinkRepository.save(new SheeridLink(trimmedEmail, url));
    }

    public boolean isPostgresEnabled() {
        return true;
    }

    public boolean isPgAllowOverwrite() {
        return pgAllowOverwrite;
    }

    private void attachSheerid(Account account) {
        if (account == null || account.getEmail() == null) {
            return;
        }
        SheeridLink link = sheeridLinkRepository.findById(account.getEmail()).orElse(null);
        account.setSheeridUrl(link == null ? null : link.getSheeridUrl());
    }

    private void attachSheerid(List<Account> accounts) {
        if (accounts == null || accounts.isEmpty()) {
            return;
        }
        List<String> emails = new ArrayList<>();
        for (Account account : accounts) {
            if (account != null && account.getEmail() != null) {
                emails.add(account.getEmail());
            }
        }
        if (emails.isEmpty()) {
            return;
        }
        List<SheeridLink> links = sheeridLinkRepository.findAllByEmailIn(emails);
        Map<String, String> map = new HashMap<>();
        for (SheeridLink link : links) {
            if (link != null && link.getEmail() != null) {
                map.put(link.getEmail(), link.getSheeridUrl());
            }
        }
        for (Account account : accounts) {
            if (account != null && account.getEmail() != null) {
                account.setSheeridUrl(map.get(account.getEmail()));
            }
        }
    }

    public record ImportResult(int added, int updated, int skipped) {
    }

    public enum ImportMode {
        OVERWRITE,
        APPEND
    }

    public enum ImportTemplate {
        AUTO,
        TOKEN,
        RECOVERY_EMAIL
    }
}
