package com.projects.webhookdeliveryservice.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

public class HashUtil {

    private HashUtil() {}

    public static String sha256(String input){
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        }catch (NoSuchAlgorithmException exe){
            throw new RuntimeException("SHA-256 not available", exe);
        }
    }

}
