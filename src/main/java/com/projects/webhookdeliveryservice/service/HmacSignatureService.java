package com.projects.webhookdeliveryservice.service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

import org.springframework.stereotype.Service;

@Service
public class HmacSignatureService {

    private static final String ALGORITHM = "HmacSHA256";

    public String sign(String secretKey, String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] signature = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(signature);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to compute HMAC-SHA256 signature", exception);
        }
    }

    public boolean verify(String secretKey, String payload, String signature) {
        if (signature == null) {
            return false;
        }
        byte[] expected = sign(secretKey, payload).getBytes(StandardCharsets.UTF_8);
        byte[] actual = signature.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }
}
