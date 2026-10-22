package com.lostfound.handover;

import java.security.SecureRandom;
import java.util.Locale;

/** Cryptographically random six-digit code; values below 100000 keep leading zeros. */
public class SecureRandomHandoverCodeGenerator implements HandoverCodeGenerator {

    private final SecureRandom random = new SecureRandom();

    @Override
    public String nextCode() {
        return String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
    }
}
