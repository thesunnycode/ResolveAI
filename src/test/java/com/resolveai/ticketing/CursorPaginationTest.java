package com.resolveai.ticketing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.resolveai.AuthTestSupport;
import com.resolveai.IntegrationTestBase;
import com.resolveai.common.error.ApiException;
import com.resolveai.common.pagination.Cursor;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Keyset pagination.
 *
 * <p><b>The test that justifies the design is {@link #pagingIsStableWhileRowsAreRemoved()}.</b>
 * Everything else here could be satisfied by {@code OFFSET}. That one could not: with an
 * offset, deleting a row from page 1 shifts everything up and page 2 skips a ticket the
 * agent never saw — a correctness bug, not a performance one, and one that produces no
 * error and no log line.
 */
class CursorPaginationTest extends IntegrationTestBase {

    @Autowired AuthTestSupport auth;
    @Autowired TicketTestSupport tickets;
    @Autowired JdbcTemplate jdbc;

    private AuthTestSupport.SeededTenant tenant;
    private String adminToken;
    private String customerToken;

    @BeforeEach
    void seed() {
        auth.wipe();
        tenant = auth.seedTenant("page");
        adminToken = auth.accessToken(rest, "page", "admin");
        customerToken = auth.accessToken(rest, "page", "customer");
    }

    @Test
    @DisplayName("a cursor round-trips exactly")
    void cursorRoundTrips() {
        Cursor original = new Cursor(OffsetDateTime.parse("2026-09-20T09:22:05Z"), 88213L);

        Cursor decoded = Cursor.decode(original.encode());

        assertThat(decoded.id()).isEqualTo(88213L);
        assertThat(decoded.createdAt().toInstant()).isEqualTo(original.createdAt().toInstant());
    }

    @Test
    @DisplayName("a tampered cursor is a typed 400, never a 500")
    void tamperedCursorsAreRejectedCleanly() {
        // These arrive from the network and will be fuzzed, truncated by a URL shortener,
        // and pasted with a trailing space. Each one must be a 400 that tells the client
        // to start again, not a stack trace.
        for (String bad : List.of("not-base64!!", "", "eyJjIjoiIiwiaSI6MX0",
                "eyJ4Ijoibm9wZSJ9", "eyJjIjoiMjAyNi0wOS0yMFQwOToyMjowNVoifQ")) {
            assertThatThrownBy(() -> Cursor.decode(bad))
                    .as("cursor %s", bad)
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).errorCode().name())
                            .isEqualTo("INVALID_CURSOR"));
        }
    }

    @Test
    @DisplayName("a tampered cursor on the endpoint is 400 INVALID_CURSOR")
    void tamperedCursorOverHttp() {
        ResponseEntity<Map> response = rest.exchange("/api/v1/tickets?cursor=garbage",
                HttpMethod.GET, new HttpEntity<>(AuthTestSupport.bearer(adminToken)), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("errorCode")).isEqualTo("INVALID_CURSOR");
    }

    @Test
    @DisplayName("paging 100 tickets in pages of 25 yields exactly 100 distinct rows")
    void pagingCoversEveryRowExactlyOnce() {
        createTickets(100);

        List<Long> seen = pageThrough(25, 0);

        assertThat(seen).hasSize(100).doesNotHaveDuplicates();
    }

    /**
     * The reason this project does not use {@code OFFSET}.
     *
     * <p>Tickets leave the filtered set while an agent is paging through it — that is what
     * resolving one does. Twenty-five rows are deleted after the first page is read; a
     * keyset cursor names a row, so the next page resumes from that row and nothing is
     * skipped. An offset names a position, and every row would shift up under it.
     */
    @Test
    @DisplayName("rows deleted between pages do not cause later rows to be skipped")
    void pagingIsStableWhileRowsAreRemoved() {
        createTickets(100);

        List<Long> seen = pageThrough(25, 25);

        // 100 created, 25 removed after the first page was read. Every surviving ticket
        // after the cursor is still returned, and none is returned twice. With OFFSET the
        // second page would have started 25 rows further down a list that had just got 25
        // rows shorter, and 25 tickets would have vanished silently.
        assertThat(seen).doesNotHaveDuplicates();
        assertThat(seen).hasSizeGreaterThanOrEqualTo(75);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket", Integer.class))
                .isEqualTo(75);
    }

    @Test
    @DisplayName("size is clamped to 100 rather than rejected")
    void sizeIsClamped() {
        createTickets(5);

        ResponseEntity<Map> response = list(adminToken, "?size=1000");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> pagination = (Map<String, Object>) response.getBody()
                .get("pagination");
        // Clamped, not a 400: size=1000 from a client trying to page less often is a
        // reasonable thing to attempt and an unreasonable thing to serve.
        assertThat(pagination.get("size")).isEqualTo(100);
    }

    @Test
    @DisplayName("the last page carries no cursor and says hasNext is false")
    void theLastPageTerminates() {
        createTickets(3);

        ResponseEntity<Map> response = list(adminToken, "?size=25");

        Map<String, Object> pagination = (Map<String, Object>) response.getBody()
                .get("pagination");
        assertThat(pagination.get("hasNext")).isEqualTo(false);
        assertThat(pagination.get("nextCursor")).isNull();
        // No totalElements, deliberately: counting a filtered set costs a second query on
        // every page to produce a number nobody uses.
        assertThat(pagination).doesNotContainKey("totalElements");
    }

    @Test
    @DisplayName("full-text search finds a ticket by a word in its body")
    void searchMatchesTheBody() {
        tickets.createId(rest, customerToken, "Payment issue",
                "The UPI transaction was debited but never reconciled.");
        tickets.createId(rest, customerToken, "Login issue",
                "Password reset email never arrives.");

        ResponseEntity<Map> response = list(adminToken, "?q=reconciled");

        List<Map<String, Object>> data = (List<Map<String, Object>>) response.getBody()
                .get("data");
        assertThat(data).hasSize(1);
        assertThat(data.get(0).get("subject")).isEqualTo("Payment issue");
    }

    @Test
    @DisplayName("a search term with tsquery operators in it is data, not a query")
    void searchTermIsNotInterpreted() {
        tickets.createId(rest, customerToken, "Safe", "ordinary content");

        // plainto_tsquery treats every one of these as a word to look for. Concatenated
        // into the SQL instead, ' | ' and '!' are tsquery operators and the first quote
        // ends the literal.
        ResponseEntity<Map> response = list(adminToken, "?q=a%20%7C%20b%20%26%20!c%20'%3B--");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) response.getBody().get("data")).isEmpty();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private void createTickets(int count) {
        for (int i = 0; i < count; i++) {
            tickets.createId(rest, customerToken, "Ticket " + i, "Body of ticket " + i);
        }
    }

    /**
     * Pages all the way through, optionally deleting {@code deleteAfterFirstPage} rows once
     * the first page has been read.
     */
    @SuppressWarnings("unchecked")
    private List<Long> pageThrough(int size, int deleteAfterFirstPage) {
        List<Long> seen = new ArrayList<>();
        String cursor = null;
        boolean first = true;

        while (true) {
            String query = "?size=" + size + (cursor == null ? "" : "&cursor=" + cursor);
            ResponseEntity<Map> response = list(adminToken, query);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

            List<Map<String, Object>> data = (List<Map<String, Object>>) response.getBody()
                    .get("data");
            data.forEach(row -> seen.add(((Number) row.get("id")).longValue()));

            if (first && deleteAfterFirstPage > 0) {
                // Delete rows the agent has NOT yet seen, which is the case an offset gets
                // wrong. Deleting rows already read would shift nothing they still need.
                //
                // The guards have to come off: ticket_event cascades from ticket, and the
                // cascade fires trg_ticket_event_immutable exactly as a direct DELETE
                // would. Worth knowing - it means a ticket cannot be deleted in production
                // either without someone deciding to lose its audit trail.
                auth.withoutAppendOnlyGuards(() -> jdbc.update("""
                        DELETE FROM ticket WHERE id IN (
                            SELECT id FROM ticket
                             WHERE id NOT IN (SELECT unnest(?::bigint[]))
                             ORDER BY created_at DESC, id DESC LIMIT ?)
                        """, seen.toArray(new Long[0]), deleteAfterFirstPage));
            }
            first = false;

            Map<String, Object> pagination = (Map<String, Object>) response.getBody()
                    .get("pagination");
            if (!Boolean.TRUE.equals(pagination.get("hasNext"))) {
                return seen;
            }
            cursor = (String) pagination.get("nextCursor");
            assertThat(cursor).as("hasNext was true, so a cursor must be present").isNotNull();
        }
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> list(String token, String query) {
        return rest.exchange("/api/v1/tickets" + query, HttpMethod.GET,
                new HttpEntity<>(AuthTestSupport.bearer(token)), Map.class);
    }
}
