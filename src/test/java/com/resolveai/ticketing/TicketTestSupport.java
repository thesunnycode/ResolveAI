package com.resolveai.ticketing;

import com.resolveai.AuthTestSupport;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * HTTP helpers for the ticketing tests.
 *
 * <p><b>Everything goes through the real endpoints.</b> Creating fixtures by calling the
 * service directly would skip the security filter, the idempotency aspect and the
 * {@code If-Match} check — which is to say, it would skip most of what these tests exist to
 * verify, while still producing tickets the assertions would happily agree with.
 */
@Component
public class TicketTestSupport {

    /**
     * A fresh idempotency key per request.
     *
     * <p>A UUID with the hyphens kept is 36 characters of {@code [A-Za-z0-9-]}, which
     * satisfies the 16–128 rule. Reusing one key across a test class would make the second
     * request a replay of the first, which is a confusing way to discover that idempotency
     * works.
     */
    public static HttpHeaders authed(String token) {
        HttpHeaders headers = AuthTestSupport.bearer(token);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return headers;
    }

    public static HttpHeaders authed(String token, String idempotencyKey) {
        HttpHeaders headers = AuthTestSupport.bearer(token);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return headers;
    }

    public static HttpHeaders withIfMatch(String token, String etag) {
        HttpHeaders headers = authed(token);
        headers.setIfMatch(etag);
        return headers;
    }

    @SuppressWarnings("unchecked")
    public ResponseEntity<Map> create(TestRestTemplate rest, String token, String subject,
                                      String body) {
        return rest.exchange("/api/v1/tickets", HttpMethod.POST,
                new HttpEntity<>(Map.of("subject", subject, "body", body), authed(token)),
                Map.class);
    }

    /** Creates a ticket and returns its id, failing loudly if creation did not work. */
    public Long createId(TestRestTemplate rest, String token, String subject, String body) {
        ResponseEntity<Map> response = create(rest, token, subject, body);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("Ticket creation failed: " + response.getStatusCode()
                    + " " + response.getBody());
        }
        return ((Number) response.getBody().get("id")).longValue();
    }

    /** The current ETag, read the way a client would: from a GET. */
    @SuppressWarnings("unchecked")
    public String etag(TestRestTemplate rest, String token, Long ticketId) {
        ResponseEntity<Map> response = rest.exchange("/api/v1/tickets/" + ticketId,
                HttpMethod.GET, new HttpEntity<>(AuthTestSupport.bearer(token)), Map.class);
        return response.getHeaders().getETag();
    }

    @SuppressWarnings("unchecked")
    public ResponseEntity<Map> get(TestRestTemplate rest, String token, Long ticketId) {
        return rest.exchange("/api/v1/tickets/" + ticketId, HttpMethod.GET,
                new HttpEntity<>(AuthTestSupport.bearer(token)), Map.class);
    }

    @SuppressWarnings("unchecked")
    public ResponseEntity<Map> post(TestRestTemplate rest, String token, Long ticketId,
                                    String action, Map<String, Object> body) {
        return rest.exchange("/api/v1/tickets/" + ticketId + "/" + action, HttpMethod.POST,
                new HttpEntity<>(body, withIfMatch(token, etag(rest, token, ticketId))),
                Map.class);
    }

    /** Same, but with an ETag the caller chose — for the stale-version assertions. */
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map> postWithEtag(TestRestTemplate rest, String token, Long ticketId,
                                            String action, Map<String, Object> body,
                                            String etag) {
        HttpHeaders headers = authed(token);
        if (etag != null) {
            headers.setIfMatch(etag);
        }
        return rest.exchange("/api/v1/tickets/" + ticketId + "/" + action, HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);
    }

    @SuppressWarnings("unchecked")
    public ResponseEntity<Map> addMessage(TestRestTemplate rest, String token, Long ticketId,
                                          String body, String visibility) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("body", body);
        if (visibility != null) {
            payload.put("visibility", visibility);
        }
        return rest.exchange("/api/v1/tickets/" + ticketId + "/messages", HttpMethod.POST,
                new HttpEntity<>(payload, authed(token)), Map.class);
    }
}
