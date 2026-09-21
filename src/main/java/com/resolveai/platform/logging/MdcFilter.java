package com.resolveai.platform.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts {@code traceId} and {@code requestId} into the MDC for the life of a request, and
 * echoes the request id back as {@code X-Request-Id}.
 *
 * <p>Registered <b>first</b> in the chain, before security, so that an authentication failure
 * is logged with the same trace id the client is given. A filter ordered after security
 * produces exactly the logs you cannot correlate: the 401s.
 *
 * <p><b>The {@code finally} block is the whole point of this class.</b> The MDC is a
 * {@code ThreadLocal}. A request that returns without clearing it leaves its trace id
 * attached to the thread, and the next request served by that thread logs under someone
 * else's id — which is worse than having no trace id at all, because it is confidently wrong
 * and sends you to read the wrong request. Virtual threads make each request a fresh carrier
 * in the common case, which is exactly what makes the leak intermittent and hard to find, so
 * this does not rely on it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MdcFilter extends OncePerRequestFilter {

    public static final String TRACE_ID = "traceId";
    public static final String REQUEST_ID = "requestId";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    /**
     * W3C trace context: {@code version-traceid-spanid-flags}, e.g.
     * {@code 00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01}. Matched strictly —
     * an id copied out of a malformed header is an id that correlates with nothing, and it
     * would reach the log aggregator as attacker-controlled text.
     */
    private static final Pattern TRACEPARENT =
            Pattern.compile("^[0-9a-f]{2}-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String traceId = traceIdFrom(request);
        String requestId = UUID.randomUUID().toString();

        MDC.put(TRACE_ID, traceId);
        MDC.put(REQUEST_ID, requestId);

        // Set before the chain runs: a response committed by a downstream filter — a security
        // rejection, say — would otherwise be sent before this header could be added.
        response.setHeader(REQUEST_ID_HEADER, requestId);

        try {
            chain.doFilter(request, response);
        } finally {
            // Remove the specific keys rather than MDC.clear(): by Phase 4 the security
            // filter adds tenantId and userId, and clear() would depend on this filter
            // running last on the way out, which is the opposite of how it is ordered.
            MDC.remove(TRACE_ID);
            MDC.remove(REQUEST_ID);
            // tenantId and userId are put in — and taken out — by JwtAuthenticationFilter,
            // which is the only place that knows them. It runs inside this filter, so its
            // finally block has already run by the time this one does.
        }
    }

    /**
     * Reuses the caller's trace id when the {@code traceparent} header carries a well-formed
     * one, so a trace started upstream stays one trace. Generates otherwise.
     */
    private static String traceIdFrom(HttpServletRequest request) {
        String traceparent = request.getHeader("traceparent");
        if (traceparent != null) {
            var m = TRACEPARENT.matcher(traceparent.trim());
            if (m.matches()) {
                return m.group(1);
            }
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** Actuator polls run every few seconds and correlating them helps nobody. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator/");
    }
}
