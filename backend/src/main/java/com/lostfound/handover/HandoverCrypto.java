package com.lostfound.handover;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

/**
 * HMAC-SHA256 digests for pickup codes. The key is loaded only here, from the
 * fixed-main `app.handover.hmac-secret` property. Digests are bound to the claim
 * id and generation so a copied digest cannot be replayed elsewhere.
 */
@Component
public class HandoverCrypto {

    private static final String ALGORITHM = "HmacSHA256";
    private static final int MIN_KEY_BYTES = 32;

    private final byte[] key;

    public HandoverCrypto(@Value("${app.handover.hmac-secret:}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "APP_HANDOVER_HMAC_SECRET is not configured. Provision it outside Git "
                            + "alongside JWT_SECRET before starting the API.");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(secret.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("APP_HANDOVER_HMAC_SECRET must be Base64-encoded.", ex);
        }
        if (decoded.length < MIN_KEY_BYTES) {
            throw new IllegalStateException(
                    "APP_HANDOVER_HMAC_SECRET must decode to at least 32 bytes (256 bits).");
        }
        this.key = decoded;
    }

    public String digest(long claimId, int generation, String code) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            String material = claimId + ":" + generation + ":" + code;
            byte[] result = mac.doFinal(material.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(result);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to compute the handover digest.", ex);
        }
    }

    /** Constant-time comparison of two stored/derived hex digests. */
    public boolean matches(String expectedHex, String actualHex) {
        if (expectedHex == null || actualHex == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expectedHex.getBytes(StandardCharsets.US_ASCII),
                actualHex.getBytes(StandardCharsets.US_ASCII));
    }
}
