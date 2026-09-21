package com.resolveai.common.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * The single place an error becomes a response body.
 *
 * <p><b>Built in Phase 3, before any endpoint exists</b>, deliberately. Every controller
 * written after this returns RFC 7807 from its first line; retrofitting an error contract
 * across sixty endpoints in Phase 9 is miserable work that gets done badly and inconsistently.
 *
 * <p>Two rules the catch-all follows, and they are the reason this class is worth reading:
 *
 * <ol>
 *   <li><b>A 500's {@code detail} is a fixed generic string.</b> Never the exception message.
 *       An exception message names classes, tables, constraint names, sometimes a row's
 *       contents — all of which is reconnaissance handed to whoever provoked the error.
 *   <li><b>The real cause is logged at ERROR with the same {@code traceId} that went into the
 *       response.</b> That is what makes rule 1 affordable: the user quotes eight hex
 *       characters and you have the stack trace, so nothing is lost by withholding it.
 * </ol>
 *
 * <p><b>{@code @Order(HIGHEST_PRECEDENCE)} is load-bearing.</b> Boot registers its own
 * {@code ProblemDetailsExceptionHandler} at order 0, and an unordered {@code @ControllerAdvice}
 * sits at {@code LOWEST_PRECEDENCE} — so without this annotation Spring answers the MVC
 * exceptions first and emits a valid RFC 7807 body that is missing {@code errorCode},
 * {@code timestamp} and {@code traceId}. It looks right, it passes a casual glance, and it
 * breaks every client that switches on {@code errorCode}. This was caught by curling a 404.
 *
 * <p>Handlers below are ordered most-specific first; Spring resolves by closest exception
 * type, not by declaration order, but reading order matters to the next person.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * The only {@code detail} a 500 ever carries. Fixed text, so it cannot accidentally
     * become an exception message during a refactor.
     */
    private static final String GENERIC_500 =
            "An internal error occurred. Quote the traceId when reporting this.";

    // ── Validation ──────────────────────────────────────────────────────────

    /** {@code @Valid} on a request body failed. Produces the populated {@code errors[]}. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiProblem> onBodyValidation(MethodArgumentNotValidException ex,
                                                HttpServletRequest request) {
        List<FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::toViolation)
                .toList();
        // Global (class-level) constraints have no field, and dropping them would leave the
        // client a 400 with an empty errors array and no idea what was wrong.
        List<FieldViolation> all = java.util.stream.Stream.concat(
                violations.stream(),
                ex.getBindingResult().getGlobalErrors().stream().map(e ->
                        FieldViolation.of(e.getObjectName(), e.getCode(), e.getDefaultMessage(), null)))
                .toList();

        return problem(ApiProblem.validation(
                all.size() + (all.size() == 1 ? " field is invalid." : " fields are invalid."),
                path(request), traceId(), all));
    }

    /** {@code @Validated} on a path variable, request param or header failed. */
    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiProblem> onParamValidation(ConstraintViolationException ex,
                                                 HttpServletRequest request) {
        List<FieldViolation> violations = ex.getConstraintViolations().stream()
                .map(v -> FieldViolation.of(
                        lastNode(v.getPropertyPath().toString()),
                        v.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName(),
                        v.getMessage(),
                        v.getInvalidValue()))
                .toList();
        return problem(ApiProblem.validation(
                "Request parameters failed validation.", path(request), traceId(), violations));
    }

    /**
     * Malformed JSON, or a value of the wrong JSON type.
     *
     * <p>The parser message is <b>not</b> forwarded: it quotes the offending input, which for
     * this API means a customer's ticket body reflected back into an error response and a log.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiProblem> onUnreadableBody(HttpMessageNotReadableException ex,
                                                HttpServletRequest request) {
        log.debug("Unreadable request body on {}", path(request), ex);
        return problem(ApiProblem.of(ErrorCode.VALIDATION_ERROR,
                "Request body is not valid JSON, or a field has the wrong type.",
                path(request), traceId()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiProblem> onTypeMismatch(MethodArgumentTypeMismatchException ex,
                                              HttpServletRequest request) {
        String required = ex.getRequiredType() == null ? "the expected type"
                : ex.getRequiredType().getSimpleName();
        return problem(ApiProblem.validation(
                "Parameter '" + ex.getName() + "' is not a valid " + required + ".",
                path(request), traceId(),
                List.of(FieldViolation.of(ex.getName(), "TypeMismatch",
                        "Expected " + required, ex.getValue()))));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ApiProblem> onMissingParam(MissingServletRequestParameterException ex,
                                              HttpServletRequest request) {
        return problem(ApiProblem.validation(
                "Required parameter '" + ex.getParameterName() + "' is missing.",
                path(request), traceId(),
                List.of(FieldViolation.of(ex.getParameterName(), "NotNull", "Required", null))));
    }

    /**
     * A required header is absent. {@code Idempotency-Key} gets its own code, because a
     * client that omitted it needs to know it is the idempotency contract it broke and not
     * some generic validation rule.
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ApiProblem> onMissingHeader(MissingRequestHeaderException ex,
                                               HttpServletRequest request) {
        if ("Idempotency-Key".equalsIgnoreCase(ex.getHeaderName())) {
            return problem(ApiProblem.of(ErrorCode.IDEMPOTENCY_KEY_REQUIRED,
                    "This endpoint requires an Idempotency-Key header of 16-128 characters "
                            + "matching [A-Za-z0-9_-].",
                    path(request), traceId()));
        }
        return problem(ApiProblem.validation(
                "Required header '" + ex.getHeaderName() + "' is missing.",
                path(request), traceId(),
                List.of(FieldViolation.of(ex.getHeaderName(), "NotNull", "Required", null))));
    }

    // ── Application errors ──────────────────────────────────────────────────

    /**
     * Anything a service threw on purpose. The code carries its own status, so there is no
     * mapping table here to fall out of sync with the enum.
     */
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiProblem> onApiException(ApiException ex, HttpServletRequest request) {
        ErrorCode code = ex.errorCode();
        if (code.status().is5xxServerError()) {
            log.error("{} on {}", code, path(request), ex);
        } else {
            log.debug("{} on {}: {}", code, path(request), ex.getMessage());
        }
        return problem(ApiProblem.of(code, ex.getMessage(), path(request), traceId()));
    }

    // ── Security ────────────────────────────────────────────────────────────

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ApiProblem> onAuthentication(AuthenticationException ex,
                                                HttpServletRequest request) {
        log.debug("Authentication failed on {}", path(request), ex);
        return problem(ApiProblem.of(ErrorCode.UNAUTHORIZED,
                "Authentication is required. Send a valid bearer token.",
                path(request), traceId()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiProblem> onAccessDenied(AccessDeniedException ex,
                                              HttpServletRequest request) {
        log.debug("Access denied on {}", path(request));
        return problem(ApiProblem.of(ErrorCode.FORBIDDEN,
                "You are authenticated, but this action is not permitted for your role.",
                path(request), traceId()));
    }

    // ── Persistence ─────────────────────────────────────────────────────────

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ApiProblem> onOptimisticLock(OptimisticLockingFailureException ex,
                                                HttpServletRequest request) {
        return problem(ApiProblem.of(ErrorCode.VERSION_CONFLICT,
                "This resource was modified by someone else. Reload and retry.",
                path(request), traceId()));
    }

    /**
     * A database constraint rejected the write.
     *
     * <p><b>The constraint name is logged, never returned.</b> It names tables and columns,
     * and several of these constraints exist precisely to enforce correctness properties an
     * attacker would like to understand. A generic 409 is the honest public answer; the log
     * line carries the detail.
     *
     * <p>Where a specific constraint has a meaningful client-facing story —
     * {@code uq_escalation_rung}, {@code uq_delivery} — the service layer catches it and
     * throws an {@link ApiException} with the right code. Reaching this handler means nobody
     * anticipated the violation, which is exactly when a generic answer is correct.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiProblem> onDataIntegrity(DataIntegrityViolationException ex,
                                               HttpServletRequest request) {
        log.warn("Constraint violation on {}", path(request), ex);
        return problem(ApiProblem.of(ErrorCode.CONFLICT,
                "The request conflicts with the current state of the resource.",
                path(request), traceId()));
    }

    // ── Protocol ────────────────────────────────────────────────────────────

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiProblem> onMethodNotSupported(HttpRequestMethodNotSupportedException ex,
                                                    HttpServletRequest request) {
        ApiProblem body = new ApiProblem(
                ErrorCode.TYPE_BASE + "method-not-allowed", "Method not allowed",
                HttpStatus.METHOD_NOT_ALLOWED.value(), "METHOD_NOT_ALLOWED",
                ex.getMethod() + " is not supported by this resource.",
                path(request), java.time.Instant.now(), traceId(), null, null);
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .headers(h -> {
                    if (ex.getSupportedHttpMethods() != null) {
                        h.setAllow(ex.getSupportedHttpMethods());
                    }
                })
                .body(body);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiProblem> onMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex,
                                                       HttpServletRequest request) {
        return problem(ApiProblem.of(ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                "Content-Type must be application/json.", path(request), traceId()));
    }

    /**
     * No handler matched. Requires {@code spring.mvc.throw-exception-if-no-handler-found},
     * without which Spring serves its own 404 and bypasses this class entirely — one of the
     * few ways a non-RFC-7807 body can still escape.
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    ResponseEntity<ApiProblem> onNoHandler(NoHandlerFoundException ex,
                                           HttpServletRequest request) {
        ApiProblem body = new ApiProblem(
                ErrorCode.TYPE_BASE + "not-found", "Not found", HttpStatus.NOT_FOUND.value(),
                "NOT_FOUND", "No endpoint matches " + ex.getHttpMethod() + " " + ex.getRequestURL()
                + ".", path(request), java.time.Instant.now(), traceId(), null, null);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }

    /**
     * Spring 6.1+ throws this instead of {@link NoHandlerFoundException} when the static
     * resource handler is involved. Both are handled, because which one you get depends on
     * {@code spring.web.resources.add-mappings} and that is too subtle a thing for the shape
     * of a 404 body to depend on.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiProblem> onNoResource(NoResourceFoundException ex,
                                            HttpServletRequest request) {
        ApiProblem body = new ApiProblem(
                ErrorCode.TYPE_BASE + "not-found", "Not found", HttpStatus.NOT_FOUND.value(),
                "NOT_FOUND", "No endpoint matches this path.", path(request),
                java.time.Instant.now(), traceId(), null, null);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }

    // ── Catch-all ───────────────────────────────────────────────────────────

    /**
     * Everything unanticipated.
     *
     * <p>The response says nothing useful about the cause, on purpose. The log line beside it
     * says everything, keyed by the same {@code traceId} the caller was handed.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiProblem> onUnexpected(Exception ex, HttpServletRequest request) {
        String traceId = traceId();
        log.error("Unhandled exception on {} {} [traceId={}]",
                request.getMethod(), path(request), traceId, ex);
        return problem(ApiProblem.of(ErrorCode.INTERNAL_ERROR, GENERIC_500, path(request), traceId));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static ResponseEntity<ApiProblem> problem(ApiProblem body) {
        return ResponseEntity.status(body.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                // An error body is never cacheable, and this is all tenant data besides.
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }

    /**
     * Read from the MDC, which {@code MdcFilter} populated from {@code traceparent} or a
     * generated id. Falls back rather than throwing: an error response with no trace id is
     * degraded, but an error <i>in the error handler</i> is a blank 500.
     */
    private static String traceId() {
        String id = MDC.get("traceId");
        return id != null ? id : "unavailable";
    }

    private static String path(HttpServletRequest request) {
        return request.getRequestURI();
    }

    private static FieldViolation toViolation(FieldError e) {
        return FieldViolation.of(e.getField(), e.getCode(), e.getDefaultMessage(),
                e.getRejectedValue());
    }

    /** {@code createTicket.arg0.subject} -> {@code subject}. */
    private static String lastNode(String propertyPath) {
        int dot = propertyPath.lastIndexOf('.');
        return dot >= 0 ? propertyPath.substring(dot + 1) : propertyPath;
    }
}
