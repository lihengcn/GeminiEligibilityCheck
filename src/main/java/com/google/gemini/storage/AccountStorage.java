package com.google.gemini.storage;

import com.google.gemini.entity.Account;
import com.google.gemini.entity.AccountStatus;
import com.google.gemini.entity.SheeridLink;
import com.google.gemini.dto.UpdateAccountRequest;
import com.google.gemini.repository.AccountRepository;
import com.google.gemini.repository.SheeridLinkRepository;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AccountStorage {
    private static final Logger log = LoggerFactory.getLogger(AccountStorage.class);

    private final AccountRepository accountRepository;
    private final SheeridLinkRepository sheeridLinkRepository;
    private final EntityManager entityManager;
    public AccountStorage(
            AccountRepository accountRepository,
            SheeridLinkRepository sheeridLinkRepository,
            EntityManager entityManager
    ) {
        this.accountRepository = accountRepository;
        this.sheeridLinkRepository = sheeridLinkRepository;
        this.entityManager = entityManager;
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
            if (parts.length >= 4) {
                boolean thirdIsRecovery = looksLikeRecoveryEmail(parts[2]);
                boolean fourthIsRecovery = looksLikeRecoveryEmail(parts[3]);
                if (thirdIsRecovery && !fourthIsRecovery) {
                    return ImportTemplate.RECOVERY_EMAIL;
                }
                if (fourthIsRecovery && !thirdIsRecovery) {
                    return ImportTemplate.TOKEN;
                }
            }
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
        private boolean hasPassword;
        private boolean hasRecoveryEmail;
        private boolean hasAuthenticatorToken;
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
            parsed.authenticatorToken = parts.length > 3 ? parts[3] : "";
            parsed.hasAuthenticatorToken = parts.length > 3 && !parsed.authenticatorToken.trim().isEmpty();
            return parsed;
        }
        parsed.authenticatorToken = parts.length > 2 ? parts[2] : "";
        parsed.hasAuthenticatorToken = parts.length > 2;
        parsed.recoveryEmail = parts.length > 3 ? parts[3] : "";
        parsed.hasRecoveryEmail = parts.length > 3 && !parsed.recoveryEmail.trim().isEmpty();
        return parsed;
    }

    @Transactional
    public ImportResult importFromText(String content, ImportTemplate template) {
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
            ParsedImportLine parsed = parseImportLine(line, template);
            if (parsed == null) {
                skipped++;
                continue;
            }
            try {
                if (importSingleLine(parsed)) {
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

    private boolean importSingleLine(ParsedImportLine parsed) {
        String email = parsed.email;
        Account existing = accountRepository.findById(email).orElse(null);
        if (existing != null) {
            applyImportUpdate(existing, parsed);
            accountRepository.save(existing);
            return false;
        }
        Account account = new Account();
        account.setEmail(email);
        account.setPassword(parsed.hasPassword ? parsed.password : "");
        account.setRecoveryEmail(parsed.hasRecoveryEmail ? parsed.recoveryEmail : "");
        account.setAuthenticatorToken(parsed.hasAuthenticatorToken ? parsed.authenticatorToken : "");
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
        if (parsed.hasRecoveryEmail) {
            account.setRecoveryEmail(parsed.recoveryEmail);
        }
        if (parsed.hasAuthenticatorToken) {
            account.setAuthenticatorToken(parsed.authenticatorToken);
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

    @Transactional
    public UpdateResult updateAccount(UpdateAccountRequest request, AccountStatus status) {
        String originalEmail = request.getOriginalEmail() == null ? "" : request.getOriginalEmail().trim();
        String newEmail = request.getEmail() == null ? "" : request.getEmail().trim();
        if (originalEmail.isEmpty() || newEmail.isEmpty()) {
            return UpdateResult.NOT_FOUND;
        }
        Account existing = accountRepository.findById(originalEmail).orElse(null);
        if (existing == null) {
            return UpdateResult.NOT_FOUND;
        }
        boolean emailChanged = !originalEmail.equals(newEmail);
        if (emailChanged && accountRepository.existsById(newEmail)) {
            return UpdateResult.EMAIL_CONFLICT;
        }
        Account target = emailChanged ? new Account() : existing;
        target.setEmail(newEmail);
        target.setPassword(request.getPassword() != null ? request.getPassword() : existing.getPassword());
        target.setRecoveryEmail(request.getRecoveryEmail() != null ? request.getRecoveryEmail() : existing.getRecoveryEmail());
        target.setAuthenticatorToken(request.getAuthenticatorToken() != null ? request.getAuthenticatorToken() : existing.getAuthenticatorToken());
        target.setStatus(status != null ? status : existing.getStatus());
        target.setSold(request.getSold() != null ? request.getSold() : existing.isSold());
        target.setFinished(request.getFinished() != null ? request.getFinished() : existing.isFinished());
        if (emailChanged) {
            accountRepository.save(target);
            accountRepository.delete(existing);
            SheeridLink link = sheeridLinkRepository.findById(originalEmail).orElse(null);
            if (link != null) {
                sheeridLinkRepository.deleteById(originalEmail);
                link.setEmail(newEmail);
                sheeridLinkRepository.save(link);
            }
        } else {
            accountRepository.save(target);
        }
        return UpdateResult.OK;
    }

    @Transactional
    public int deleteAccounts(List<String> emails) {
        if (emails == null || emails.isEmpty()) {
            return 0;
        }
        List<String> normalized = new ArrayList<>();
        for (String email : emails) {
            if (email == null) {
                continue;
            }
            String trimmed = email.trim();
            if (!trimmed.isEmpty()) {
                normalized.add(trimmed);
            }
        }
        if (normalized.isEmpty()) {
            return 0;
        }
        List<Account> existing = accountRepository.findAllById(normalized);
        if (existing.isEmpty()) {
            return 0;
        }
        accountRepository.deleteAll(existing);
        return existing.size();
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

    public enum UpdateResult {
        OK,
        NOT_FOUND,
        EMAIL_CONFLICT
    }

    public enum ImportTemplate {
        AUTO,
        TOKEN,
        RECOVERY_EMAIL
    }
}
