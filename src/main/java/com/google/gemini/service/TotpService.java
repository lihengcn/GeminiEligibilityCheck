package com.google.gemini.service;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

@Service
public class TotpService {
    private static final int CODE_DIGITS = 6;
    private static final int TIME_STEP_SECONDS = 30;
    private static final int MIN_VALID_SECONDS = 3;

    public TotpResult generateToken(String secret) throws Exception {
        TotpResult first = generate(secret, Instant.now().getEpochSecond());
        if (first.secondsRemaining <= MIN_VALID_SECONDS) {
            Thread.sleep(MIN_VALID_SECONDS * 1000L);
            TotpResult second = generate(secret, Instant.now().getEpochSecond());
            return second;
        }
        return first;
    }

    private TotpResult generate(String secret, long epochSeconds) throws Exception {
        byte[] key = decodeBase32(secret);
        long counter = epochSeconds / TIME_STEP_SECONDS;
        byte[] counterBytes = ByteBuffer.allocate(8).putLong(counter).array();

        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(key, "HmacSHA1"));
        byte[] hash = mac.doFinal(counterBytes);

        int offset = hash[hash.length - 1] & 0x0f;
        int binary = ((hash[offset] & 0x7f) << 24)
                | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8)
                | (hash[offset + 3] & 0xff);
        int otp = binary % (int) Math.pow(10, CODE_DIGITS);
        String code = String.format("%0" + CODE_DIGITS + "d", otp);

        int secondsRemaining = TIME_STEP_SECONDS - (int) (epochSeconds % TIME_STEP_SECONDS);
        return new TotpResult(code, secondsRemaining);
    }

    private byte[] decodeBase32(String input) {
        String normalized = input.trim().replace("=", "").replace(" ", "").toUpperCase();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("secret empty");
        }
        ByteBuffer buffer = ByteBuffer.allocate((normalized.length() * 5) / 8);
        int bits = 0;
        int value = 0;
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            int index = base32Index(c);
            value = (value << 5) | index;
            bits += 5;
            if (bits >= 8) {
                buffer.put((byte) ((value >> (bits - 8)) & 0xff));
                bits -= 8;
            }
        }
        buffer.flip();
        byte[] out = new byte[buffer.remaining()];
        buffer.get(out);
        return out;
    }

    private int base32Index(char c) {
        if (c >= 'A' && c <= 'Z') {
            return c - 'A';
        }
        if (c >= '2' && c <= '7') {
            return 26 + (c - '2');
        }
        throw new IllegalArgumentException("invalid base32 char");
    }

    public static class TotpResult {
        private final String code;
        private final int secondsRemaining;

        private TotpResult(String code, int secondsRemaining) {
            this.code = code;
            this.secondsRemaining = secondsRemaining;
        }

        public String getCode() {
            return code;
        }

        public int getSecondsRemaining() {
            return secondsRemaining;
        }
    }
}
