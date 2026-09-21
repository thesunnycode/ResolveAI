package com.resolveai.platform.idempotency;

import com.resolveai.common.error.ApiException;
import com.resolveai.common.error.ErrorCode;
import com.resolveai.platform.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;
import tools.jackson.databind.ObjectMapper;

/**
 * Enforces the {@code Idempotency-Key} contract on any handler marked {@link Idempotent}.
 *
 * <h2>Why an aspect and not a {@code HandlerInterceptor}</h2>
 *
 * <p>An interceptor sees bytes. To replay a stored response it would have to buffer the
 * servlet output stream, and to hash the request it would have to buffer and re-expose the
 * input stream before the message converters read it — two wrappers, an ordering constraint
 * between them, and a body read twice. Here the advice sits around the controller method,
 * so the request is already a bound DTO and the response is the object about to be
 * serialised. <b>Replay becomes "return this object instead of calling the method."</b>
 *
 * <h2>The hash is over the bound arguments, not the raw bytes</h2>
 *
 * <p>Which means {@code {"a":1,"b":2}} and {@code {"b":2,"a":1}} are the same request, and
 * so are two bodies differing only in whitespace. Hashing raw bytes would call those
 * conflicts and return {@code 422} to a client that did nothing wrong — a retry through a
 * proxy that re-encodes JSON is enough to trigger it. The canonical form is the serialised
 * DTO, which also means a field the DTO does not bind cannot affect the hash, matching the
 * fact that it cannot affect the outcome either.
 *
 * <h2>{@code @Order(LOWEST_PRECEDENCE)}</h2>
 *
 * <p>So that {@code @PreAuthorize} runs first. An unauthorised caller must not be able to
 * burn another tenant's idempotency key, and — more practically — the key is namespaced by
 * tenant id, which does not exist until authentication has happened.
 */
@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class IdempotencyAspect {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyAspect.class);

    static final String HEADER = "Idempotency-Key";

    /**
     * Doc 05: 16–128 characters of {@code [A-Za-z0-9_-]}.
     *
     * <p>The character class is not cosmetic — the value becomes part of a Redis key, and a
     * key containing a newline or a wildcard is a small injection surface. The lower bound
     * is what makes the key a key: an eight-character value picked by a client is short
     * enough to collide between two genuinely different requests.
     */
    private static final Pattern VALID_KEY = Pattern.compile("[A-Za-z0-9_-]{16,128}");

    private final IdempotencyStore store;
    private final ObjectMapper objectMapper;

    public IdempotencyAspect(IdempotencyStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    @Around("@annotation(com.resolveai.platform.idempotency.Idempotent)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            // Not an HTTP call — a test invoking the controller directly, or a future
            // internal caller. There is no key to enforce and nothing to replay.
            return pjp.proceed();
        }

        String key = requireValidKey(request.getHeader(HEADER));
        Long tenantId = TenantContext.getRequired();
        String endpoint = endpointOf(request);
        String redisKey = "idem:" + tenantId + ":" + endpoint + ":" + key;
        String requestHash = hashOf(pjp);

        var claim = store.claim(redisKey, tenantId, key, endpoint);

        if (claim instanceof IdempotencyStore.Claim.InFlight) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "A request with this Idempotency-Key is still being processed. "
                    + "Retry in a moment.");
        }
        if (claim instanceof IdempotencyStore.Claim.Replay replay) {
            return replayOrConflict(pjp, replay.stored(), requestHash);
        }

        // Fresh. The key is claimed; from here every exit path either stores a response or
        // releases the claim, or a retry would be locked out for two minutes.
        boolean stored = false;
        try {
            Object result = pjp.proceed();
            store.store(redisKey, tenantId, key, endpoint, new IdempotencyStore.StoredResponse(
                    requestHash, successStatusOf(pjp), objectMapper.writeValueAsString(result)));
            stored = true;
            return result;
        } finally {
            if (!stored) {
                // The handler threw. A failed attempt must not be replayable — the client
                // is expected to retry with the same key and get a real attempt.
                store.release(redisKey);
            }
        }
    }

    /**
     * Same key, same request: hand back what was returned the first time. Same key,
     * different request: {@code 422}, never a silent replay of the wrong response.
     */
    private Object replayOrConflict(ProceedingJoinPoint pjp,
                                    IdempotencyStore.StoredResponse stored, String requestHash) {
        if (!stored.requestHash().equals(requestHash)) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                    "This Idempotency-Key was already used for a different request body. "
                    + "Use a new key.");
        }
        log.debug("Replaying stored response for an idempotent request");
        Class<?> returnType = ((MethodSignature) pjp.getSignature()).getReturnType();
        return objectMapper.readValue(stored.body(), returnType);
    }

    private static String requireValidKey(String key) {
        if (key == null || !VALID_KEY.matcher(key).matches()) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED,
                    "This endpoint requires an Idempotency-Key header of 16-128 characters "
                    + "matching [A-Za-z0-9_-].");
        }
        return key;
    }

    /**
     * {@code POST /api/v1/tickets} — the <i>pattern</i>, not the resolved path.
     *
     * <p>Namespacing by endpoint means the same key reused against a different operation is
     * a different record rather than a spurious conflict. Using the resolved URI instead
     * would make {@code /tickets/1/messages} and {@code /tickets/2/messages} separate
     * namespaces, which is wrong in the other direction.
     */
    private static String endpointOf(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return request.getMethod() + " " + (pattern != null ? pattern : request.getRequestURI());
    }

    /** The status the handler would return on success, so the replay reports the same one. */
    private static int successStatusOf(ProceedingJoinPoint pjp) {
        ResponseStatus annotation = ((MethodSignature) pjp.getSignature()).getMethod()
                .getAnnotation(ResponseStatus.class);
        return annotation != null ? annotation.value().value() : HttpStatus.OK.value();
    }

    /**
     * SHA-256 over the canonical JSON of the handler's arguments.
     *
     * <p>The principal is skipped: it is derived from the token, not from the request body,
     * and including it would make a key unreplayable after a token refresh.
     */
    private String hashOf(ProceedingJoinPoint pjp) {
        StringBuilder canonical = new StringBuilder();
        for (Object arg : pjp.getArgs()) {
            if (arg == null || arg instanceof com.resolveai.iam.security.ResolvePrincipal) {
                continue;
            }
            canonical.append(objectMapper.writeValueAsString(arg)).append('');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }

    private static HttpServletRequest currentRequest() {
        var attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servlet
                ? servlet.getRequest() : null;
    }
}
