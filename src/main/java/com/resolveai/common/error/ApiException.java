package com.resolveai.common.error;

/**
 * The exception services throw. Carries an {@link ErrorCode}, so the handler needs no
 * mapping table and the status cannot drift between two throw sites for the same failure.
 *
 * <p>Deliberately unchecked: an {@code ApiException} is a terminal answer to the request, not
 * a condition a caller is expected to recover from, and threading {@code throws} clauses
 * through every service signature buys nothing.
 *
 * <p><b>The stack trace is suppressed for client errors.</b> A 404 or a 409 is a normal
 * outcome of a well-behaved API — filling one in costs a stack walk on a hot path and buries
 * the genuine 500s in log noise. Server-side codes keep theirs, because those are the ones
 * worth debugging.
 */
public class ApiException extends RuntimeException {

    private final transient ErrorCode errorCode;

    public ApiException(ErrorCode errorCode, String detail) {
        this(errorCode, detail, null);
    }

    public ApiException(ErrorCode errorCode, String detail, Throwable cause) {
        super(detail, cause, false, errorCode.status().is5xxServerError());
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    // ── Shorthands for the codes thrown most often ──────────────────────────

    public static ApiException notFound(ErrorCode code, String what, Object id) {
        return new ApiException(code, what + " " + id + " was not found.");
    }

    public static ApiException forbidden(String detail) {
        return new ApiException(ErrorCode.FORBIDDEN, detail);
    }

    public static ApiException conflict(ErrorCode code, String detail) {
        return new ApiException(code, detail);
    }
}
