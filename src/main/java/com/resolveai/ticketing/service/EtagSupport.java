package com.resolveai.ticketing.service;

import com.resolveai.common.error.ApiException;
import com.resolveai.common.error.ErrorCode;

/**
 * Translates between a JPA {@code @Version} integer and an HTTP entity tag.
 *
 * <p><b>Weak tags ({@code W/"7"}).</b> A strong tag promises byte-for-byte equality of the
 * representation, and this one does not: the same ticket version serialises differently for
 * an agent and for its requester, and {@code include=timeline} changes it again. A weak tag
 * promises semantic equivalence, which is exactly what a version column gives.
 */
public final class EtagSupport {

    private EtagSupport() {
    }

    public static String etagOf(int version) {
        return "W/\"" + version + "\"";
    }

    /**
     * Parses an {@code If-Match} header.
     *
     * <p>Tolerant of the strong form and of surrounding whitespace, because proxies and
     * client libraries rewrite these. Rejects {@code *} — it means "if the resource exists
     * at all", which would defeat the point of asking for the version.
     */
    public static int parseIfMatch(String header) {
        if (header == null || header.isBlank()) {
            throw new ApiException(ErrorCode.PRECONDITION_REQUIRED,
                    "This endpoint requires an If-Match header carrying the ETag from your "
                    + "last read, e.g. If-Match: W/\"7\".");
        }
        String value = header.trim();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        value = value.replace("\"", "").trim();
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCode.PRECONDITION_REQUIRED,
                    "If-Match must be an ETag previously returned by this API, e.g. W/\"7\".", e);
        }
    }

    /**
     * The check itself.
     *
     * <p>Advisory: {@code @Version} on the entity is the real guarantee, and Hibernate
     * raises {@code OptimisticLockingFailureException} — mapped to the same {@code 409} —
     * if two writers get past this. What the early check buys is a cheap, clear failure
     * before any work is done, rather than a rollback after it.
     */
    public static void requireMatch(String ifMatchHeader, int currentVersion) {
        int expected = parseIfMatch(ifMatchHeader);
        if (expected != currentVersion) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT,
                    "This ticket was modified by someone else (you have version " + expected
                    + ", it is now at " + currentVersion + "). Reload and retry.");
        }
    }
}
