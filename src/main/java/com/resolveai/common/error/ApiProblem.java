package com.resolveai.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * The RFC 7807 body, returned by every error path in the API as
 * {@code application/problem+json}.
 *
 * <p>This is a record rather than Spring's {@code ProblemDetail} for one reason: doc 05
 * specifies {@code errorCode}, {@code timestamp} and {@code traceId} as first-class fields,
 * and {@code ProblemDetail} would carry them in its loose {@code properties} map — where they
 * serialise in an unpredictable order, cannot be validated, and are invisible to anyone
 * reading the type. <b>The contract names these fields, so the type names them too.</b>
 *
 * @param type      identifies the error class. A URI because RFC 7807 says so; it is not
 *                  dereferenced.
 * @param title     short, human-readable, stable for a given {@code type}
 * @param status    duplicated from the HTTP status line, per RFC 7807 §3.1
 * @param errorCode <b>the machine-readable contract.</b> Clients switch on this, never on
 *                  {@code detail}
 * @param detail    human prose, specific to this occurrence. May change without a version
 *                  bump, which is precisely why clients must not parse it. Always a fixed
 *                  generic string for a 500
 * @param instance  the request path
 * @param timestamp when the error was produced, UTC
 * @param traceId   the OpenTelemetry trace id, so a user reporting an error hands you a
 *                  string that finds the exact request — across the async worker boundary,
 *                  where a request id alone would stop at the HTTP layer
 * @param errors    field violations; present only for {@code VALIDATION_ERROR}
 * @param allowedTransitions the permitted targets; present only for
 *                  {@code ILLEGAL_TRANSITION}, so a client can recover without hard-coding
 *                  the state machine
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiProblem(
        String type,
        String title,
        int status,
        String errorCode,
        String detail,
        String instance,
        Instant timestamp,
        String traceId,
        List<FieldViolation> errors,
        List<String> allowedTransitions) {

    public static ApiProblem of(ErrorCode code, String detail, String instance, String traceId) {
        return new ApiProblem(
                code.type(), code.title(), code.status().value(), code.name(),
                detail, instance, Instant.now(), traceId, null, null);
    }

    public static ApiProblem validation(String detail, String instance, String traceId,
                                        List<FieldViolation> errors) {
        ErrorCode code = ErrorCode.VALIDATION_ERROR;
        return new ApiProblem(
                code.type(), code.title(), code.status().value(), code.name(),
                detail, instance, Instant.now(), traceId, errors, null);
    }

    /** Adds the recovery information that makes a 409 on the state machine actionable. */
    public ApiProblem withAllowedTransitions(List<String> allowed) {
        return new ApiProblem(type, title, status, errorCode, detail, instance, timestamp,
                traceId, errors, allowed);
    }
}
