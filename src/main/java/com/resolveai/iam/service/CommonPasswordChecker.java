package com.resolveai.iam.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Rejects passwords from a known-common list.
 *
 * <p><b>This does more for account safety than any composition rule.</b> A symbol-and-digit
 * requirement is satisfied by {@code P@ssw0rd!}, which appears in every credential-stuffing
 * list there is; length plus a blocklist is what current NIST guidance actually recommends.
 *
 * <p>Loaded once into a {@code HashSet} at startup: the check is on the registration path,
 * and re-reading a file per request to do an O(1) lookup would be silly.
 */
@Component
public class CommonPasswordChecker {

    private static final Logger log = LoggerFactory.getLogger(CommonPasswordChecker.class);
    private static final String RESOURCE = "security/common-passwords.txt";

    private final Set<String> common;

    public CommonPasswordChecker() {
        this.common = load();
        log.info("Loaded {} common passwords for registration screening", common.size());
    }

    public boolean isCommon(String password) {
        return password != null && common.contains(password.toLowerCase(Locale.ROOT));
    }

    private static Set<String> load() {
        Set<String> set = new HashSet<>(1200);
        ClassPathResource resource = new ClassPathResource(RESOURCE);
        if (!resource.exists()) {
            // Degrading to "no screening" silently would be worse than the missing file:
            // registration keeps working and the control is simply gone.
            throw new IllegalStateException(RESOURCE + " is missing from the classpath");
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim().toLowerCase(Locale.ROOT);
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    set.add(trimmed);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + RESOURCE, e);
        }
        return Set.copyOf(set);
    }
}
