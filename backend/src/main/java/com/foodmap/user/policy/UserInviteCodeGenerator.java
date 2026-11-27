package com.foodmap.user.policy;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

@Component
public class UserInviteCodeGenerator {
    // 每个字符提供 5 bit 随机性。字符均可用于 URL，去掉容易和数字混淆的 I、L、O。
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final int ADMIN_LENGTH = 26;
    private static final int REFERRAL_LENGTH = 16;

    private final SecureRandom random = new SecureRandom();

    public String newAdminCode() {
        return generate(ADMIN_LENGTH);
    }

    public String newReferralCode() {
        return generate(REFERRAL_LENGTH);
    }

    public byte[] hash(String code) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String generate(int length) {
        char[] code = new char[length];
        for (int index = 0; index < length; index++) {
            code[index] = ALPHABET.charAt(random.nextInt(ALPHABET.length()));
        }
        return new String(code);
    }
}
