package com.resolveai.platform.outbox;

/**
 * "This will never work, stop trying."
 *
 * <p>The default on failure is to retry five times with backoff, which is right for a
 * timeout, a deadlock or a provider having a bad minute. It is wrong for a payload that
 * cannot be parsed or an aggregate that has since been deleted: those fail identically
 * every time, and the only thing five attempts buy is a worker slot held for several
 * minutes and four more identical lines in the log.
 *
 * <p>Throwing this sends the event straight to {@code DEAD} on attempt one, where a human
 * can look at it. <b>Distinguishing these two cases is what stops a poison message from
 * becoming an outage</b> — without it, a thousand malformed events and a thousand
 * transient ones are indistinguishable, and the queue drains five times slower than it
 * should exactly when it is under the most pressure.
 */
public class NonRetryableException extends RuntimeException {

    public NonRetryableException(String message) {
        super(message);
    }

    public NonRetryableException(String message, Throwable cause) {
        super(message, cause);
    }
}
