package com.google.gemini.service;

import com.google.gemini.entity.Account;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

@Service
public class ResultExportService {
    // 结果文件（工作目录下）
    private final Path resultsPath = Path.of("results.txt");

    public synchronized void appendResult(Account account) throws Exception {
        // 追加写入，避免并发交织。
        String line = String.join(";",
                account.getEmail(),
                account.getStatus().name(),
                account.getPassword(),
                account.getAuthenticatorUrl()
        );
        try (BufferedWriter writer = Files.newBufferedWriter(
                resultsPath,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND)) {
            writer.write(line);
            writer.newLine();
        }
    }
}
