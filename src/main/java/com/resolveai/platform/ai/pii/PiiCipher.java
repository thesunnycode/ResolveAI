package com.resolveai.platform.ai.pii;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AES-GCM for the original values behind the placeholders.
 *
 * <h2>Why encrypted rather than just stored</h2>
 *
 * <p>{@code pii_redaction_map} is the one table in this system that holds personal data
 * in isolation from its context — every card number, phone number and government id a
 * tenant's customers have ever typed, in one place, with a type label next to each. That
 * is a far more attractive target than the tickets they came from. A database backup, a
 * misconfigured read replica or a support engineer with a psql session should not be
 * enough to read it, and the key lives outside the database, so none of those three are.
 *
 * <h2>GCM, not CBC</h2>
 *
 * <p>GCM is authenticated: a modified ciphertext fails to decrypt rather than decrypting
 * to something else. Without that, an attacker who can write to the table can change what
 * a placeholder rehydrates to — and rehydration happens when rendering for a human, so
 * the result would be shown to an agent as though it were the customer's own words.
 *
 * <p><b>The nonce is random per value and stored with the ciphertext.</b> Reusing a nonce
 * under one key in GCM is catastrophic — it leaks the XOR of the plaintexts and, worse,
 * the authentication key. Twelve random bytes prepended to each value is the standard
 * answer and costs nothing.
 */
@Component
public class PiiCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    /** Published, fixed, and refused under prod by configuration: a key everybody has is
     * not a key. It exists so `docker compose up` works without ceremony. */
    private static final String DEV_ONLY_KEY = "ZGV2LW9ubHktcGlpLWtleS0zMi1ieXRlcy1sb25nISE=";

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    /**
     * @param base64Key 32 bytes, base64-encoded, from {@code PII_ENCRYPTION_KEY} via
     *                  {@code resolveai.security.pii-encryption-key}. Blank falls back to
     *                  {@link #DEV_ONLY_KEY}; {@code application-prod.yml} declares the
     *                  variable with no default, so production cannot start without a
     *                  real one.
     */
    public PiiCipher(@Value("${resolveai.security.pii-encryption-key:}") String base64Key) {
        // Blank means a developer running the stack without setting PII_ENCRYPTION_KEY.
        // A fixed, published dev key is better than refusing to boot (they would set
        // some other fixed key) and better than generating one per start (which would
        // make yesterday's redaction map unreadable today). Under prod the property is
        // required with no default, so this branch cannot be reached there.
        String effective = base64Key == null || base64Key.isBlank()
                ? DEV_ONLY_KEY : base64Key;
        byte[] decoded = Base64.getDecoder().decode(effective);
        if (decoded.length != 32) {
            throw new IllegalStateException(
                    "PII_ENCRYPTION_KEY must be 32 bytes base64-encoded (AES-256); got "
                    + decoded.length);
        }
        this.key = new SecretKeySpec(decoded, "AES");
    }

    /** @return nonce ‖ ciphertext ‖ tag, ready for a {@code BYTEA} column */
    public byte[] encrypt(String plaintext) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] out = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, out, 0, nonce.length);
            System.arraycopy(ciphertext, 0, out, nonce.length, ciphertext.length);
            return out;
        } catch (Exception e) {
            // Deliberately does not include the plaintext, or its length, or anything
            // derived from it. An exception message from this method ends up in a log.
            throw new IllegalStateException("PII encryption failed", e);
        }
    }

    public String decrypt(byte[] stored) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_BITS, stored, 0, NONCE_BYTES));
            byte[] plaintext = cipher.doFinal(stored, NONCE_BYTES, stored.length - NONCE_BYTES);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("PII decryption failed — wrong key, or the "
                                            + "ciphertext has been modified", e);
        }
    }
}
