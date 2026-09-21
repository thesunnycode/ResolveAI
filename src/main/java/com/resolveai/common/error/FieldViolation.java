package com.resolveai.common.error;

/**
 * One rejected field inside a {@link ApiProblem}'s {@code errors} array.
 *
 * @param field         the property path, e.g. {@code subject} or {@code attachmentIds[2]}
 * @param code          the Bean Validation annotation that failed, e.g. {@code NotBlank}.
 *                      Machine-readable, so a client can localise its own message.
 * @param message       human prose, for a developer reading the response
 * @param rejectedValue <b>truncated and redacted.</b> Never echo a 27 KB body, and never echo
 *                      a field whose name looks like a credential — an error response is a
 *                      log line somewhere, and a password in a log is a password leaked.
 */
public record FieldViolation(String field, String code, String message, String rejectedValue) {

    /** Anything matching this has its value replaced wholesale rather than truncated. */
    private static final String SENSITIVE = "(?i).*(password|secret|token|apikey|api_key|credential|authorization).*";

    private static final int MAX_LENGTH = 100;

    public static FieldViolation of(String field, String code, String message, Object rejected) {
        return new FieldViolation(field, code, message, redact(field, rejected));
    }

    private static String redact(String field, Object rejected) {
        if (rejected == null) {
            return null;
        }
        if (field != null && field.matches(SENSITIVE)) {
            return "«redacted»";
        }
        String s = rejected.toString();
        // Report the size rather than a 100-character prefix: for an oversized body the
        // useful fact is "27431 chars", not the first sentence of the customer's ticket.
        return s.length() > MAX_LENGTH ? "<" + s.length() + " chars>" : s;
    }
}
