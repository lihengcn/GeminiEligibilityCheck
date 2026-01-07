package com.google.gemini.storage;

import com.google.gemini.entity.Account;
import com.google.gemini.entity.AccountStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PostConstruct;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AccountStorage {
    private static final Logger log = LoggerFactory.getLogger(AccountStorage.class);
    private final Map<String, Account> accounts = new LinkedHashMap<>();

    private final ObjectMapper objectMapper;
    private final Path persistencePath;

    private final String pgUrl;
    private final String pgUser;
    private final String pgPassword;
    private final boolean pgMigrateFromFile;
    private final boolean pgAllowOverwrite;
    private final boolean pgRequired;
    private volatile boolean usePostgres;

    public AccountStorage(
            ObjectMapper objectMapper,
            @Value("${gemini.storage.path:accounts-state.json}") String persistencePath,
            @Value("${gemini.pg.url:}") String pgUrl,
            @Value("${gemini.pg.user:}") String pgUser,
            @Value("${gemini.pg.password:}") String pgPassword,
            @Value("${gemini.pg.migrateFromFile:false}") boolean pgMigrateFromFile,
            @Value("${gemini.pg.allowOverwrite:false}") boolean pgAllowOverwrite,
            @Value("${gemini.pg.required:false}") boolean pgRequired
    ) {
        this.objectMapper = objectMapper;
        this.persistencePath = Path.of(persistencePath);
        this.pgUrl = pgUrl == null ? "" : pgUrl.trim();
        this.pgUser = pgUser == null ? "" : pgUser.trim();
        this.pgPassword = pgPassword == null ? "" : pgPassword;
        this.pgMigrateFromFile = pgMigrateFromFile;
        this.pgAllowOverwrite = pgAllowOverwrite;
        this.pgRequired = pgRequired;
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

    @PostConstruct
    public void init() {
        this.usePostgres = !this.pgUrl.isBlank();
        if (usePostgres) {
            initPostgres();
            if (pgMigrateFromFile) {
                migrateFromLocalFileIfDbEmpty();
            }
            return;
        }
        loadFromDisk();
    }

    private synchronized void loadFromDisk() {
        try {
            if (!Files.exists(persistencePath)) {
                return;
            }
            List<Account> list = objectMapper.readValue(
                    Files.readAllBytes(persistencePath),
                    new TypeReference<>() {
                    }
            );
            accounts.clear();
            if (list == null) {
                return;
            }
            for (Account account : list) {
                if (account == null || account.getEmail() == null || account.getEmail().isBlank()) {
                    continue;
                }
                accounts.put(account.getEmail(), account);
            }
            log.info("Loaded {} accounts from {}", accounts.size(), persistencePath.toAbsolutePath());
        } catch (Exception ex) {
            log.warn("Failed to load accounts from {}: {}", persistencePath.toAbsolutePath(), ex.getMessage());
        }
    }

    private void persistToDisk() {
        if (usePostgres) {
            return;
        }
        try {
            Path parent = persistencePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = persistencePath.resolveSibling(persistencePath.getFileName() + ".tmp");
            byte[] bytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(listAccounts());
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, persistencePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicFail) {
                Files.move(tmp, persistencePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ex) {
            log.warn("Failed to persist accounts to {}: {}", persistencePath.toAbsolutePath(), ex.getMessage());
        }
    }

    private Connection openPg() throws Exception {
        if (pgUser.isBlank()) {
            return DriverManager.getConnection(pgUrl);
        }
        return DriverManager.getConnection(pgUrl, pgUser, pgPassword);
    }

    private void initPostgres() {
        try (Connection conn = openPg(); Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS gem_accounts (
                      email TEXT PRIMARY KEY,
                      password TEXT NOT NULL DEFAULT '',
                      authenticator_token TEXT NOT NULL DEFAULT '',
                      app_password TEXT NOT NULL DEFAULT '',
                      authenticator_url TEXT NOT NULL DEFAULT '',
                      messages_url TEXT NOT NULL DEFAULT '',
                      sold BOOLEAN NOT NULL DEFAULT FALSE,
                      finished BOOLEAN NOT NULL DEFAULT FALSE,
                      status TEXT NOT NULL DEFAULT 'IDLE',
                      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                    );
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS gem_sheerid_links (
                      email TEXT PRIMARY KEY REFERENCES gem_accounts(email) ON DELETE CASCADE,
                      sheerid_url TEXT NOT NULL DEFAULT '',
                      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                    );
                    """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_gem_accounts_status_sold ON gem_accounts(status, sold);");
            log.info("PostgreSQL enabled: {}", pgUrl);
        } catch (Exception ex) {
            if (pgRequired) {
                throw new IllegalStateException("PostgreSQL is required but init failed: " + ex.getMessage(), ex);
            }
            log.warn("Failed to init PostgreSQL (falling back to local file): {}", ex.getMessage());
            this.usePostgres = false;
            loadFromDisk();
        }
    }

    private boolean dbIsEmpty() {
        try (Connection conn = openPg();
             PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM gem_accounts LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            return !rs.next();
        } catch (Exception ex) {
            log.warn("Failed to query DB emptiness: {}", ex.getMessage());
            return false;
        }
    }

    private void migrateFromLocalFileIfDbEmpty() {
        if (!Files.exists(persistencePath)) {
            return;
        }
        if (!dbIsEmpty()) {
            return;
        }
        try {
            List<Account> list = objectMapper.readValue(
                    Files.readAllBytes(persistencePath),
                    new TypeReference<>() {
                    }
            );
            if (list == null || list.isEmpty()) {
                return;
            }
            int migrated = 0;
            try (Connection conn = openPg()) {
                conn.setAutoCommit(false);
                for (Account a : list) {
                    if (a == null || a.getEmail() == null || a.getEmail().isBlank()) {
                        continue;
                    }
                    upsertAccountPreserveStatus(conn, a, false);
                    migrated++;
                }
                conn.commit();
            }
            log.info("Migrated {} accounts from {} into PostgreSQL", migrated, persistencePath.toAbsolutePath());
        } catch (Exception ex) {
            log.warn("Failed to migrate from local file {}: {}", persistencePath.toAbsolutePath(), ex.getMessage());
        }
    }

    private static Account mapRow(ResultSet rs) throws Exception {
        Account a = new Account();
        a.setEmail(rs.getString("email"));
        a.setPassword(rs.getString("password"));
        a.setAuthenticatorToken(rs.getString("authenticator_token"));
        a.setAppPassword(rs.getString("app_password"));
        a.setAuthenticatorUrl(rs.getString("authenticator_url"));
        a.setMessagesUrl(rs.getString("messages_url"));
        try {
            a.setSheeridUrl(rs.getString("sheerid_url"));
        } catch (Exception ignored) {
            a.setSheeridUrl(null);
        }
        a.setSold(rs.getBoolean("sold"));
        a.setFinished(rs.getBoolean("finished"));
        String status = rs.getString("status");
        try {
            a.setStatus(AccountStatus.valueOf(status));
        } catch (Exception ignored) {
            a.setStatus(AccountStatus.IDLE);
        }
        return a;
    }

    private void upsertAccountPreserveStatus(Connection conn, Account account, boolean keepFlags) throws Exception {
        String sql = keepFlags
                ? """
                INSERT INTO gem_accounts(email,password,authenticator_token,app_password,authenticator_url,messages_url)
                VALUES (?,?,?,?,?,?)
                ON CONFLICT (email) DO UPDATE SET
                  password=EXCLUDED.password,
                  authenticator_token=EXCLUDED.authenticator_token,
                  app_password=EXCLUDED.app_password,
                  authenticator_url=EXCLUDED.authenticator_url,
                  messages_url=EXCLUDED.messages_url,
                  updated_at=NOW()
                """
                : """
                INSERT INTO gem_accounts(email,password,authenticator_token,app_password,authenticator_url,messages_url,sold,finished,status)
                VALUES (?,?,?,?,?,?,?,?,?)
                ON CONFLICT (email) DO UPDATE SET
                  password=EXCLUDED.password,
                  authenticator_token=EXCLUDED.authenticator_token,
                  app_password=EXCLUDED.app_password,
                  authenticator_url=EXCLUDED.authenticator_url,
                  messages_url=EXCLUDED.messages_url,
                  sold=EXCLUDED.sold,
                  finished=EXCLUDED.finished,
                  status=EXCLUDED.status,
                  updated_at=NOW()
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, account.getEmail());
            ps.setString(2, account.getPassword() == null ? "" : account.getPassword());
            ps.setString(3, account.getAuthenticatorToken() == null ? "" : account.getAuthenticatorToken());
            ps.setString(4, account.getAppPassword() == null ? "" : account.getAppPassword());
            ps.setString(5, account.getAuthenticatorUrl() == null ? "" : account.getAuthenticatorUrl());
            ps.setString(6, account.getMessagesUrl() == null ? "" : account.getMessagesUrl());
            if (!keepFlags) {
                ps.setBoolean(7, account.isSold());
                ps.setBoolean(8, account.isFinished());
                ps.setString(9, account.getStatus() == null ? AccountStatus.IDLE.name() : account.getStatus().name());
            }
            ps.executeUpdate();
        }
    }

    public synchronized ImportResult importFromText(String content, ImportMode mode) {
        if (usePostgres) {
            return importFromTextPostgres(content, mode);
        }
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
            if (parts.length < 3) {
                skipped++;
                continue;
            }
            String email = parts[0];
            if (looksLikeHeaderEmail(email)) {
                skipped++;
                continue;
            }
            String password = parts.length > 1 ? parts[1] : "";
            String authenticatorToken = parts.length > 2 ? parts[2] : "";
            String appPassword = parts.length > 3 ? parts[3] : "";
            String authenticatorUrl = parts.length > 4 ? parts[4] : "";
            String messagesUrl = parts.length > 5 ? parts[5] : "";
            Account existing = accounts.get(email);
            if (existing != null) {
                // 追加模式下保留原状态，仅更新账号资料。
                existing.setPassword(password);
                existing.setAuthenticatorToken(authenticatorToken);
                existing.setAppPassword(appPassword);
                existing.setAuthenticatorUrl(authenticatorUrl);
                existing.setMessagesUrl(messagesUrl);
                updated++;
                continue;
            }
            Account account = new Account(
                    email,
                    password,
                    authenticatorToken,
                    appPassword,
                    authenticatorUrl,
                    messagesUrl,
                    null,
                    false,
                    false,
                    AccountStatus.IDLE
            );
            accounts.put(email, account);
            added++;
        }
        persistToDisk();
        return new ImportResult(added, updated, skipped);
    }

    private ImportResult importFromTextPostgres(String content, ImportMode mode) {
        if (mode == ImportMode.OVERWRITE && !pgAllowOverwrite) {
            mode = ImportMode.APPEND;
        }
        int added = 0;
        int updated = 0;
        int skipped = 0;
        String[] lines = content.split("\\r?\\n");
        try (Connection conn = openPg()) {
            conn.setAutoCommit(false);
            if (mode == ImportMode.OVERWRITE) {
                try (Statement st = conn.createStatement()) {
                    st.executeUpdate("DELETE FROM gem_accounts");
                }
            }
            String existsSql = "SELECT 1 FROM gem_accounts WHERE email = ?";
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
                if (parts.length < 3) {
                    skipped++;
                    continue;
                }
                String email = parts[0];
                if (looksLikeHeaderEmail(email)) {
                    skipped++;
                    continue;
                }
                boolean existed;
                try (PreparedStatement ps = conn.prepareStatement(existsSql)) {
                    ps.setString(1, email);
                    try (ResultSet rs = ps.executeQuery()) {
                        existed = rs.next();
                    }
                }

                String password = parts.length > 1 ? parts[1] : "";
                String authenticatorToken = parts.length > 2 ? parts[2] : "";
                String appPassword = parts.length > 3 ? parts[3] : "";
                String authenticatorUrl = parts.length > 4 ? parts[4] : "";
                String messagesUrl = parts.length > 5 ? parts[5] : "";

                Account account = new Account(
                        email,
                        password,
                        authenticatorToken,
                        appPassword,
                        authenticatorUrl,
                        messagesUrl,
                        null,
                        false,
                        false,
                        AccountStatus.IDLE
                );
                // 追加模式：保留 sold/finished/status，仅更新账号资料。
                boolean keepFlags = existed && mode == ImportMode.APPEND;
                try {
                    upsertAccountPreserveStatus(conn, account, keepFlags);
                } catch (Exception ex) {
                    skipped++;
                    continue;
                }

                if (existed) {
                    updated++;
                } else {
                    added++;
                }
            }
            conn.commit();
        } catch (Exception ex) {
            log.warn("PostgreSQL import failed: {}", ex.getMessage());
        }
        return new ImportResult(added, updated, skipped);
    }

    public synchronized Account pollAccount() {
        if (usePostgres) {
            return pollAccountPostgres();
        }
        // 同步锁保证并发下不会分配到同一个账号。
        for (Account account : accounts.values()) {
            if (!account.isSold() && account.getStatus() == AccountStatus.IDLE) {
                account.setStatus(AccountStatus.CHECKING);
                persistToDisk();
                return account;
            }
        }
        return null;
    }

    private Account pollAccountPostgres() {
        String sql = """
                WITH candidate AS (
                  SELECT email
                  FROM gem_accounts
                  WHERE sold = FALSE AND status = 'IDLE'
                  ORDER BY email
                  LIMIT 1
                  FOR UPDATE SKIP LOCKED
                )
                UPDATE gem_accounts a
                SET status = 'CHECKING', updated_at = NOW()
                FROM candidate
                WHERE a.email = candidate.email
                RETURNING a.*;
                """;
        try (Connection conn = openPg()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                Account account = null;
                if (rs.next()) {
                    account = mapRow(rs);
                }
                conn.commit();
                return account;
            }
        } catch (Exception ex) {
            log.warn("PostgreSQL poll failed: {}", ex.getMessage());
            return null;
        }
    }

    public synchronized Account updateStatus(String email, AccountStatus status) {
        if (usePostgres) {
            return updateStatusPostgres(email, status);
        }
        Account account = accounts.get(email);
        if (account == null) {
            return null;
        }
        // 仅在内存中更新状态。
        account.setStatus(status);
        persistToDisk();
        return account;
    }

    private Account updateStatusPostgres(String email, AccountStatus status) {
        if (email == null || email.isBlank() || status == null) {
            return null;
        }
        String sql = "UPDATE gem_accounts SET status = ?, updated_at = NOW() WHERE email = ? RETURNING *";
        try (Connection conn = openPg();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status.name());
            ps.setString(2, email);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return mapRow(rs);
            }
        } catch (Exception ex) {
            log.warn("PostgreSQL updateStatus failed: {}", ex.getMessage());
            return null;
        }
    }

    public synchronized int resetCheckingToIdle() {
        if (usePostgres) {
            return resetCheckingToIdlePostgres();
        }
        // 将所有检查中的账号重置为空闲。
        int reset = 0;
        for (Account account : accounts.values()) {
            if (account.getStatus() == AccountStatus.CHECKING) {
                account.setStatus(AccountStatus.IDLE);
                reset++;
            }
        }
        if (reset > 0) {
            persistToDisk();
        }
        return reset;
    }

    private int resetCheckingToIdlePostgres() {
        String sql = "UPDATE gem_accounts SET status='IDLE', updated_at = NOW() WHERE status='CHECKING'";
        try (Connection conn = openPg(); PreparedStatement ps = conn.prepareStatement(sql)) {
            return ps.executeUpdate();
        } catch (Exception ex) {
            log.warn("PostgreSQL resetCheckingToIdle failed: {}", ex.getMessage());
            return 0;
        }
    }

    public synchronized boolean restoreStatus(String email, AccountStatus status) {
        if (usePostgres) {
            return restoreStatusPostgres(email, status);
        }
        if (email == null || email.isBlank() || status == null) {
            return false;
        }
        Account account = accounts.get(email);
        if (account == null) {
            return false;
        }
        account.setStatus(status);
        persistToDisk();
        return true;
    }

    private boolean restoreStatusPostgres(String email, AccountStatus status) {
        if (email == null || email.isBlank() || status == null) {
            return false;
        }
        String sql = "UPDATE gem_accounts SET status=?, updated_at = NOW() WHERE email=?";
        try (Connection conn = openPg(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status.name());
            ps.setString(2, email);
            return ps.executeUpdate() > 0;
        } catch (Exception ex) {
            log.warn("PostgreSQL restoreStatus failed: {}", ex.getMessage());
            return false;
        }
    }

    public synchronized boolean updateSold(String email, boolean sold) {
        if (usePostgres) {
            return updateSoldPostgres(email, sold);
        }
        if (email == null || email.isBlank()) {
            return false;
        }
        Account account = accounts.get(email);
        if (account == null) {
            return false;
        }
        account.setSold(sold);
        persistToDisk();
        return true;
    }

    private boolean updateSoldPostgres(String email, boolean sold) {
        if (email == null || email.isBlank()) {
            return false;
        }
        String sql = "UPDATE gem_accounts SET sold=?, updated_at = NOW() WHERE email=?";
        try (Connection conn = openPg(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, sold);
            ps.setString(2, email);
            return ps.executeUpdate() > 0;
        } catch (Exception ex) {
            log.warn("PostgreSQL updateSold failed: {}", ex.getMessage());
            return false;
        }
    }

    public synchronized boolean updateFinished(String email, boolean finished) {
        if (usePostgres) {
            return updateFinishedPostgres(email, finished);
        }
        if (email == null || email.isBlank()) {
            return false;
        }
        Account account = accounts.get(email);
        if (account == null) {
            return false;
        }
        account.setFinished(finished);
        persistToDisk();
        return true;
    }

    private boolean updateFinishedPostgres(String email, boolean finished) {
        if (email == null || email.isBlank()) {
            return false;
        }
        String sql = "UPDATE gem_accounts SET finished=?, updated_at = NOW() WHERE email=?";
        try (Connection conn = openPg(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, finished);
            ps.setString(2, email);
            return ps.executeUpdate() > 0;
        } catch (Exception ex) {
            log.warn("PostgreSQL updateFinished failed: {}", ex.getMessage());
            return false;
        }
    }

    public synchronized boolean deleteAccount(String email) {
        if (usePostgres) {
            return deleteAccountPostgres(email);
        }
        if (email == null || email.isBlank()) {
            return false;
        }
        Account removed = accounts.remove(email);
        if (removed == null) {
            return false;
        }
        persistToDisk();
        return true;
    }

    private boolean deleteAccountPostgres(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        String sql = "DELETE FROM gem_accounts WHERE email=?";
        try (Connection conn = openPg(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, email);
            return ps.executeUpdate() > 0;
        } catch (Exception ex) {
            log.warn("PostgreSQL deleteAccount failed: {}", ex.getMessage());
            return false;
        }
    }

    public synchronized List<Account> listAccounts() {
        if (usePostgres) {
            return listAccountsPostgres();
        }
        return new ArrayList<>(accounts.values());
    }

    public synchronized boolean exists(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        String trimmed = email.trim();
        if (usePostgres) {
            return existsPostgres(trimmed);
        }
        return accounts.containsKey(trimmed);
    }

    private List<Account> listAccountsPostgres() {
        String sql = """
                SELECT a.*, l.sheerid_url
                FROM gem_accounts a
                LEFT JOIN gem_sheerid_links l ON l.email = a.email
                ORDER BY a.email
                """;
        List<Account> list = new ArrayList<>();
        try (Connection conn = openPg();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(mapRow(rs));
            }
        } catch (Exception ex) {
            log.warn("PostgreSQL listAccounts failed: {}", ex.getMessage());
        }
        return list;
    }

    public synchronized void upsertSheeridUrl(String email, String sheeridUrl) {
        if (email == null || email.isBlank()) {
            return;
        }
        String trimmedEmail = email.trim();
        String url = sheeridUrl == null ? "" : sheeridUrl.trim();
        if (usePostgres) {
            upsertSheeridUrlPostgres(trimmedEmail, url);
            return;
        }
        Account account = accounts.get(trimmedEmail);
        if (account == null) {
            return;
        }
        account.setSheeridUrl(url.isEmpty() ? null : url);
        persistToDisk();
    }

    private boolean existsPostgres(String email) {
        String sql = "SELECT 1 FROM gem_accounts WHERE email = ? LIMIT 1";
        try (Connection conn = openPg(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception ex) {
            log.warn("PostgreSQL exists failed: {}", ex.getMessage());
            return false;
        }
    }

    private void upsertSheeridUrlPostgres(String email, String url) {
        String sql = """
                INSERT INTO gem_sheerid_links(email, sheerid_url)
                VALUES (?, ?)
                ON CONFLICT (email) DO UPDATE SET
                  sheerid_url = EXCLUDED.sheerid_url,
                  updated_at = NOW()
                """;
        try (Connection conn = openPg(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, email);
            ps.setString(2, url == null ? "" : url);
            ps.executeUpdate();
        } catch (Exception ex) {
            log.warn("PostgreSQL upsertSheeridUrl failed: {}", ex.getMessage());
        }
    }

    public boolean isPostgresEnabled() {
        return usePostgres;
    }

    public boolean isPgAllowOverwrite() {
        return pgAllowOverwrite;
    }

    public record ImportResult(int added, int updated, int skipped) {
    }

    public enum ImportMode {
        OVERWRITE,
        APPEND
    }
}
