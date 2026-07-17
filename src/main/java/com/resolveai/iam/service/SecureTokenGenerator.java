package com.resolveai.iam.service;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * One URL-safe random token generator, shared by every feature that needs an unguessable
 * link (team invites, password reset) rather than each rolling its own.
 */
public final class SecureTokenGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private SecureTokenGenerator() {
    }

    public static String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
