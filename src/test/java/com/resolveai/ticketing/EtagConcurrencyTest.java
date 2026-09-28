package com.resolveai.ticketing;

import static org.assertj.core.api.Assertions.assertThat;

import com.resolveai.AuthTestSupport;
import com.resolveai.IntegrationTestBase;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * {@code ETag} / {@code If-Match} optimistic concurrency.
 *
 * <p><b>What this buys:</b> two agents have the same ticket open. One edits it. The
 * second's edit is refused with a code that says exactly what happened, instead of silently
 * overwriting the first — which is the default behaviour of any API that does not do this,
 * and which nobody notices until a customer is told two different things.
 */
class EtagConcurrencyTest extends IntegrationTestBase {

    @Autowired AuthTestSupport auth;
    @Autowired TicketTestSupport tickets;

    private String agentToken;
    private String customerToken;

    @BeforeEach
    void seed() {
        auth.wipe();
        auth.seedTenant("etag");
        agentToken = auth.accessToken(rest, "etag", "agent");
        customerToken = auth.accessToken(rest, "etag", "customer");
    }

    @Test
    @DisplayName("GET exposes a weak ETag, and PATCH with it succeeds")
    void currentEtagIsAccepted() {
        Long id = tickets.createId(rest, customerToken, "Original subject", "Body");
        String etag = tickets.etag(rest, agentToken, id);

        // Weak, deliberately. The same ticket version serialises differently for an agent
        // and its requester, so a strong tag would be a promise the API cannot keep.
        assertThat(etag).startsWith("W/\"");

        ResponseEntity<Map> response = patch(id, Map.of("subject", "Edited subject"), etag);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("subject")).isEqualTo("Edited subject");
        assertThat(response.getHeaders().getETag()).isNotEqualTo(etag);
    }

    @Test
    @DisplayName("a stale ETag is 409 VERSION_CONFLICT, not a silent overwrite")
    void staleEtagIsRejected() {
        Long id = tickets.createId(rest, customerToken, "Original", "Body");
        String stale = tickets.etag(rest, agentToken, id);

        // Somebody else edits first.
        assertThat(patch(id, Map.of("subject", "First writer wins"), stale).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> second = patch(id, Map.of("subject", "Second writer"), stale);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody().get("errorCode")).isEqualTo("VERSION_CONFLICT");
        // And the first writer's edit survived.
        assertThat(tickets.get(rest, agentToken, id).getBody().get("subject"))
                .isEqualTo("First writer wins");
    }

    @Test
    @DisplayName("a missing If-Match is 428 Precondition Required")
    void missingIfMatchIs428() {
        Long id = tickets.createId(rest, customerToken, "Original", "Body");

        ResponseEntity<Map> response = patch(id, Map.of("subject", "No precondition"), null);

        // The documented pick. Doc 10 leaves this open between 400 and 428; 428 says what
        // actually happened - the request was well-formed and is being refused until it is
        // made conditional - where 400 would invite the client to go and fix the body.
        assertThat(response.getStatusCode().value()).isEqualTo(428);
        assertThat(response.getBody().get("errorCode")).isEqualTo("PRECONDITION_REQUIRED");
    }

    @Test
    @DisplayName("an unparseable If-Match is 428, not a 500")
    void garbledIfMatchIs428() {
        Long id = tickets.createId(rest, customerToken, "Original", "Body");

        ResponseEntity<Map> response = patch(id, Map.of("subject", "Bad tag"), "W/\"not-a-number\"");

        assertThat(response.getStatusCode().value()).isEqualTo(428);
    }

    @Test
    @DisplayName("If-Match is required on assign, status, resolve and reopen too")
    void everyMutationIsGuarded() {
        Long id = tickets.createId(rest, customerToken, "Guarded", "Body");

        for (var action : Map.of(
                "assign", Map.<String, Object>of(),
                "status", Map.<String, Object>of("status", "TRIAGED"),
                "resolve", Map.<String, Object>of("resolution", "Done"),
                "reopen", Map.<String, Object>of("reason", "Not done")).entrySet()) {

            ResponseEntity<Map> response = tickets.postWithEtag(rest, agentToken, id,
                    action.getKey(), action.getValue(), null);

            assertThat(response.getStatusCode().value())
                    .as("POST /%s without If-Match", action.getKey())
                    .isEqualTo(428);
        }
    }

    @Test
    @DisplayName("adding a message needs no If-Match, because an append is not an edit")
    void messagesAreNotGuarded() {
        Long id = tickets.createId(rest, customerToken, "Thread", "Body");

        // Two agents replying at once should both succeed. Requiring a matching version
        // here would reject the second for a conflict that does not exist - they are not
        // overwriting each other, they are both appending.
        ResponseEntity<Map> response = tickets.addMessage(rest, agentToken, id, "Reply", "PUBLIC");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("a ticket the caller cannot see is 404 even before the If-Match check")
    void invisibleTicketIs404NotPreconditionRequired() {
        Long id = tickets.createId(rest, customerToken, "Mine", "Body");
        String otherTenantToken = otherTenantAgent();

        ResponseEntity<Map> response = tickets.postWithEtag(rest, otherTenantToken, id,
                "assign", Map.of(), null);

        // A 428 here would confirm the ticket exists, which is the same leak the 404-not-403
        // rule exists to close. The visibility check has to come first.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("errorCode")).isEqualTo("TICKET_NOT_FOUND");
    }

    private String otherTenantAgent() {
        auth.seedTenant("etag2");
        return auth.accessToken(rest, "etag2", "agent");
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> patch(Long id, Map<String, Object> body, String etag) {
        HttpHeaders headers = TicketTestSupport.authed(agentToken);
        if (etag != null) {
            headers.set(HttpHeaders.IF_MATCH, etag);
        }
        return rest.exchange("/api/v1/tickets/" + id, HttpMethod.PATCH,
                new HttpEntity<>(new HashMap<>(body), headers), Map.class);
    }
}
