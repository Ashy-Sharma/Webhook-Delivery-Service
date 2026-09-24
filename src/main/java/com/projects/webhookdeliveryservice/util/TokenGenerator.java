package com.projects.webhookdeliveryservice.util;

import java.security.SecureRandom;
import java.util.Base64;

public class TokenGenerator {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int TOKEN_BYTE_LENGTH = 32;

    public static String generateToken(){
        byte[] randomBytes = new byte[TOKEN_BYTE_LENGTH];
        SECURE_RANDOM.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

}
