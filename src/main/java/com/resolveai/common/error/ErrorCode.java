package com.resolveai.common.error;

import org.springframework.http.HttpStatus;

/**
 * Every error the API can return, with its default status and its {@code type} URI suffix.
 *
 * <p><b>This enum is the machine-readable half of the error contract.</b> Clients switch on
 * {@code errorCode}; they never parse {@code detail}, which is human prose and may change
 * without a version bump. Keeping the codes in one enum rather than as string literals
 * scattered through services means the set is enumerable, the statuses cannot drift between
 * two throw sites, and adding a code is a visible diff.
 *
 * <p>Codes are drawn from
 * <a href="../../../../../../../docs/planning/05-API-CONTRACT.md">doc 05</a>. Where doc 05
 * names a code, it appears here with the same spelling — a contract that disagrees with its
 * implementation on the exact string is worse than no contract.
 */
public enum ErrorCode {

    // ── Validation and request shape ────────────────────────────────────────
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "validation"),
    INVALID_CURSOR(HttpStatus.BAD_REQUEST, "invalid-cursor"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported-media-type"),
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "payload-too-large"),

    // ── Authentication and authorization ────────────────────────────────────
    /**
     * Covers "no such user", "wrong password" and "wrong tenant" alike. Distinguishing them
     * turns the login endpoint into a user-enumeration oracle, so they share one code, one
     * status and one message.
     */
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "invalid-credentials"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "unauthorized"),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "invalid-refresh-token"),
    /**
     * A refresh token was presented twice. The system cannot tell whether the attacker is the
     * presenter or the original holder, so the whole token family is revoked.
     */
    TOKEN_REUSE_DETECTED(HttpStatus.UNAUTHORIZED, "token-reuse-detected"),
    ACCOUNT_DISABLED(HttpStatus.FORBIDDEN, "account-disabled"),
    /**
     * Authenticated, the resource is in your scope, but <b>this action</b> is not permitted.
     * Never used for a resource the caller cannot see — that is a 404, deliberately, because
     * a 403 would confirm the resource exists and turn every id into a probe.
     */
    FORBIDDEN(HttpStatus.FORBIDDEN, "forbidden"),

    // ── Idempotency ─────────────────────────────────────────────────────────
    IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST, "idempotency-key-required"),
    /** Same key, different body. Never a silent replay of the wrong response. */
    IDEMPOTENCY_KEY_CONFLICT(HttpStatus.UNPROCESSABLE_ENTITY, "idempotency-key-conflict"),
    /** Redis is unavailable and idempotency fails closed. */
    IDEMPOTENCY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "idempotency-unavailable"),

    // ── Tenancy and registration ────────────────────────────────────────────
    TENANT_NOT_FOUND(HttpStatus.NOT_FOUND, "tenant-not-found"),
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "email-already-exists"),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "user-not-found"),

    // ── Tickets ─────────────────────────────────────────────────────────────
    /** Also returned for another tenant's or another customer's ticket. See {@link #FORBIDDEN}. */
    TICKET_NOT_FOUND(HttpStatus.NOT_FOUND, "ticket-not-found"),
    TICKET_CLOSED(HttpStatus.CONFLICT, "ticket-closed"),
    ALREADY_ASSIGNED(HttpStatus.CONFLICT, "already-assigned"),
    ILLEGAL_TRANSITION(HttpStatus.CONFLICT, "illegal-transition"),
    AGENT_AT_CAPACITY(HttpStatus.UNPROCESSABLE_ENTITY, "agent-at-capacity"),
    VERSION_CONFLICT(HttpStatus.CONFLICT, "version-conflict"),

    // ── Triage and drafting ─────────────────────────────────────────────────
    TRIAGE_IN_PROGRESS(HttpStatus.CONFLICT, "triage-in-progress"),
    DRAFT_NOT_FOUND(HttpStatus.NOT_FOUND, "draft-not-found"),
    DRAFT_IN_PROGRESS(HttpStatus.CONFLICT, "draft-in-progress"),
    INVALID_DRAFT_REFERENCE(HttpStatus.UNPROCESSABLE_ENTITY, "invalid-draft-reference"),
    ACTION_ALREADY_RECORDED(HttpStatus.CONFLICT, "action-already-recorded"),
    NO_KNOWLEDGE_BASE(HttpStatus.UNPROCESSABLE_ENTITY, "no-knowledge-base"),
    /**
     * 402 Payment Required is rarely used and exactly right here. It is distinguishable from
     * {@link #RATE_LIMITED}, which matters: a client should retry after a 429 and must not
     * after a 402.
     */
    AI_BUDGET_EXHAUSTED(HttpStatus.PAYMENT_REQUIRED, "ai-budget-exhausted"),
    AI_DISABLED_BY_POLICY(HttpStatus.SERVICE_UNAVAILABLE, "ai-disabled-by-policy"),
    AI_PROVIDER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "ai-provider-unavailable"),

    // ── SLA ─────────────────────────────────────────────────────────────────
    SLA_ALREADY_TERMINAL(HttpStatus.CONFLICT, "sla-already-terminal"),
    INVALID_PAUSE_REASON(HttpStatus.UNPROCESSABLE_ENTITY, "invalid-pause-reason"),
    OVERLAPPING_POLICY(HttpStatus.CONFLICT, "overlapping-policy"),
    EFFECTIVE_DATE_IN_PAST(HttpStatus.UNPROCESSABLE_ENTITY, "effective-date-in-past"),

    // ── Incidents ───────────────────────────────────────────────────────────
    INCIDENT_NOT_FOUND(HttpStatus.NOT_FOUND, "incident-not-found"),
    INVALID_INCIDENT_STATE(HttpStatus.CONFLICT, "invalid-incident-state"),
    TICKET_ALREADY_LINKED(HttpStatus.CONFLICT, "ticket-already-linked"),
    LINK_NOT_FOUND(HttpStatus.NOT_FOUND, "link-not-found"),

    // ── Knowledge ───────────────────────────────────────────────────────────
    DOCUMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "document-not-found"),

    // ── Attachments ─────────────────────────────────────────────────────────
    ATTACHMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "attachment-not-found"),
    ATTACHMENT_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "attachment-too-large"),
    ATTACHMENT_NOT_UPLOADED(HttpStatus.CONFLICT, "attachment-not-uploaded"),
    /** Declared MIME type disagrees with the object's actual magic bytes. */
    CONTENT_TYPE_MISMATCH(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "content-type-mismatch"),

    // ── Platform ────────────────────────────────────────────────────────────
    EVENT_NOT_FOUND(HttpStatus.NOT_FOUND, "event-not-found"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "rate-limited"),
    CONFLICT(HttpStatus.CONFLICT, "conflict"),
    /**
     * The catch-all. Its {@code detail} is always a fixed generic string — the exception
     * message goes to the log with the same {@code traceId}, never to the client.
     */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "internal");

    /** Base for every {@code type} URI. Not dereferenced by clients; it identifies, it does not fetch. */
    public static final String TYPE_BASE = "https://resolveai.dev/errors/";

    private final HttpStatus status;
    private final String typeSuffix;

    ErrorCode(HttpStatus status, String typeSuffix) {
        this.status = status;
        this.typeSuffix = typeSuffix;
    }

    public HttpStatus status() {
        return status;
    }

    public String type() {
        return TYPE_BASE + typeSuffix;
    }

    /**
     * A human-readable title derived from the constant name: {@code TICKET_NOT_FOUND} becomes
     * "Ticket not found". Derived rather than stored so a new code cannot ship with a title
     * that contradicts it.
     */
    public String title() {
        String words = name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }
}
