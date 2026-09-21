package com.resolveai.platform.outbox;

import java.util.regex.Pattern;

/**
 * Makes an exception message safe to store.
 *
 * <h2>Why an error message is a privacy problem</h2>
 *
 * <p>{@code last_error} looks like operational metadata and is treated like it: read in
 * the DLQ screen, copied into a ticket, pasted into a chat, shipped to whatever collects
 * logs. But the exceptions that reach it come from code that was handling a customer's
 * ticket, and an exception message routinely quotes its input — a JSON parse error prints
 * the document, a validation error prints the value, a constraint violation prints the
 * row. <b>A ticket body contains phone numbers, order ids and email addresses</b>, so
 * "just store the message" quietly copies PII into a table nobody thinks of as sensitive,
 * with none of the retention rules that apply to the ticket itself.
 *
 * <p>This is a blunt instrument on purpose: it is a last line of defence on a path that
 * should not be carrying personal data at all, not a replacement for the real redactor
 * (Phase 6B Task 12) that runs on everything sent to a model. Blunt and always-on beats
 * precise and sometimes-applied for something that has to work while an incident is
 * already in progress.
 */
public final class ErrorScrubber {

    /** Long enough for the stack frame and the cause, short enough for the column. */
    private static final int MAX_LENGTH = 1_000;

    private static final Pattern EMAIL =
            Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]+");
    /** Ten or more consecutive digits: phone numbers, card numbers, long account ids. */
    private static final Pattern LONG_DIGITS = Pattern.compile("\\b\\d[\\d\\s-]{8,}\\d\\b");
    /** Indian-format identifiers that appear in this product's domain. */
    private static final Pattern UPI =
            Pattern.compile("\\b[\\w.-]{2,}@(?:ok\\w+|upi|paytm|ybl)\\b");

    private ErrorScrubber() {
    }

    public static String scrub(Throwable error) {
        if (error == null) {
            return null;
        }
        String message = error.getClass().getSimpleName()
                + (error.getMessage() == null ? "" : ": " + error.getMessage());
        return scrub(message);
    }

    public static String scrub(String message) {
        if (message == null) {
            return null;
        }
        String scrubbed = UPI.matcher(message).replaceAll("[upi]");
        scrubbed = EMAIL.matcher(scrubbed).replaceAll("[email]");
        scrubbed = LONG_DIGITS.matcher(scrubbed).replaceAll("[number]");
        // Truncated last, so a redaction is never cut in half into something that looks
        // like real data again.
        return scrubbed.length() <= MAX_LENGTH ? scrubbed
                : scrubbed.substring(0, MAX_LENGTH - 3) + "...";
    }
}
