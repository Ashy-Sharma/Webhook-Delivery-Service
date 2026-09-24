package com.projects.webhookdeliveryservice.util;

import java.security.SecureRandom;
import java.util.Base64;

public class SecretKeyGeneratorUtil {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private SecretKeyGeneratorUtil() {}

    public static String generateSecret() {
        byte[] secret = new byte[32]; // 256 bits
        SECURE_RANDOM.nextBytes(secret);

        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(secret);
    }

}
