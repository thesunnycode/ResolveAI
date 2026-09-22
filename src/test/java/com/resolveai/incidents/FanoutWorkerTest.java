package com.resolveai.incidents;

import static org.assertj.core.api.Assertions.assertThat;

import com.resolveai.AuthTestSupport;
import com.resolveai.IntegrationTestBase;
import com.resolveai.platform.outbox.OutboxEvent;
import com.resolveai.platform.outbox.OutboxRepository;
import com.resolveai.platform.outbox.WorkerRuntime;
import com.resolveai.ticketing.TicketTestSupport;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** Doc 12 8D: publish -> N independent deliveries -> fan-out, with per-delivery isolation. */
class FanoutWorkerTest extends IntegrationTestBase {

    @Autowired AuthTestSupport auth;
    @Autowired IncidentTestSupport fixtures;
    @Autowired TicketTestSupport tickets;
    @Autowired WorkerRuntime runtime;
    @Autowired FanoutWorker fanoutWorker;
    @Autowired OutboxRepository outboxRepo;
    @Autowired JdbcTemplate jdbc;

    private AuthTestSupport.SeededTenant tenant;
    private String adminToken;
    private String customerToken;

    @BeforeEach
    void seed() {
        auth.wipe();
        tenant = auth.seedTenant("fanout");
        adminToken = auth.accessToken(rest, "fanout", "admin");
        customerToken = auth.accessToken(rest, "fanout", "customer");
    }

    private Long plainTicket(String subject) {
        ResponseEntity<Map> created = tickets.create(rest, customerToken, subject,
                "Body for " + subject);
        return ((Number) created.getBody().get("id")).longValue();
    }

    @Test
    @DisplayName("publishing to 8 linked tickets creates 8 deliveries and 8 events; fan-out sends all 8")
    void publishAndFanoutAll() {
        List<Long> ticketIds = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            ticketIds.add(plainTicket("Storm ticket " + i));
        }
        Long incidentId = fixtures.seedIncident(tenant.tenantId(), "INC-FANOUT-1", 8,
                OffsetDateTime.now());
        ticketIds.forEach(id -> fixtures.linkTicket(incidentId, id, 0.9));

        ResponseEntity<Map> publish = rest.exchange("/api/v1/incidents/" + incidentId + "/updates",
                HttpMethod.POST, new HttpEntity<>(
                        Map.of("body", "We are investigating a payment issue", "visibility", "PUBLIC"),
                        authed(adminToken)),
                Map.class);

        assertThat(publish.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        Long updateId = ((Number) publish.getBody().get("updateId")).longValue();
        Map<String, Object> fanout = (Map<String, Object>) publish.getBody().get("fanout");
        assertThat(fanout.get("total")).isEqualTo(8);
        assertThat(fanout.get("status")).isEqualTo("QUEUED");

        Integer pendingDeliveries = jdbc.queryForObject(
                "SELECT count(*) FROM incident_update_delivery WHERE incident_update_id = ? "
                + "AND status = 'PENDING'", Integer.class, updateId);
        assertThat(pendingDeliveries).isEqualTo(8);
        List<OutboxEvent> events = outboxRepo.findByAggregate("INCIDENT_UPDATE", updateId);
        assertThat(events).hasSize(8);

        int processed = runtime.runOnce(fanoutWorker);
        assertThat(processed).isEqualTo(8);

        ResponseEntity<Map> statusResponse = rest.exchange(
                "/api/v1/incidents/" + incidentId + "/updates/" + updateId + "/deliveries",
                HttpMethod.GET, new HttpEntity<>(AuthTestSupport.bearer(adminToken)), Map.class);
        Map<String, Object> summary = (Map<String, Object>) statusResponse.getBody().get("summary");
        assertThat(summary.get("total")).isEqualTo(8);
        assertThat(summary.get("sent")).isEqualTo(8);
        assertThat(summary.get("pending")).isEqualTo(0);
        assertThat(summary.get("failed")).isEqualTo(0);

        Integer notifications = jdbc.queryForObject(
                "SELECT count(*) FROM notification WHERE kind = 'INCIDENT_UPDATE'", Integer.class);
        assertThat(notifications).isEqualTo(8);
        Integer publicMessages = jdbc.queryForObject(
                "SELECT count(*) FROM ticket_message WHERE visibility = 'PUBLIC' "
                + "AND body = 'We are investigating a payment issue'", Integer.class);
        assertThat(publicMessages).isEqualTo(8);
    }

    @Test
    @DisplayName("replaying an already-sent delivery event creates no additional notification")
    void redeliveryIsANoOp() {
        Long ticketId = plainTicket("Single ticket");
        Long incidentId = fixtures.seedIncident(tenant.tenantId(), "INC-FANOUT-2", 1,
                OffsetDateTime.now());
        fixtures.linkTicket(incidentId, ticketId, 0.9);

        rest.exchange("/api/v1/incidents/" + incidentId + "/updates", HttpMethod.POST,
                new HttpEntity<>(Map.of("body", "Update one", "visibility", "INTERNAL"),
                        authed(adminToken)),
                Map.class);

        assertThat(runtime.runOnce(fanoutWorker)).isEqualTo(1);
        Integer afterFirst = jdbc.queryForObject(
                "SELECT count(*) FROM notification WHERE kind = 'INCIDENT_UPDATE'", Integer.class);
        assertThat(afterFirst).isEqualTo(1);

        List<OutboxEvent> events = outboxRepo.findByAggregate("INCIDENT_UPDATE",
                jdbc.queryForObject("SELECT id FROM incident_update WHERE incident_id = ?",
                        Long.class, incidentId));
        fanoutWorker.process(events.get(0));

        Integer afterReplay = jdbc.queryForObject(
                "SELECT count(*) FROM notification WHERE kind = 'INCIDENT_UPDATE'", Integer.class);
        assertThat(afterReplay).isEqualTo(1);
    }

    @Test
    @DisplayName("one delivery pointed at a missing ticket fails in isolation; the other stays SENT with exactly one notification")
    void oneFailureDoesNotAffectTheOther() {
        Long goodTicket = plainTicket("Good ticket");
        Long badTicket = plainTicket("Ticket to be removed");
        Long incidentId = fixtures.seedIncident(tenant.tenantId(), "INC-FANOUT-3", 2,
                OffsetDateTime.now());
        fixtures.linkTicket(incidentId, goodTicket, 0.9);
        fixtures.linkTicket(incidentId, badTicket, 0.9);

        rest.exchange("/api/v1/incidents/" + incidentId + "/updates", HttpMethod.POST,
                new HttpEntity<>(Map.of("body", "Two-ticket update", "visibility", "INTERNAL"),
                        authed(adminToken)),
                Map.class);

        // The delivery row itself is left alone (its FK to a real ticket must hold); what
        // is corrupted is the outbox event's own payload, standing in for whatever
        // transient fault the API contract's own example names - "notification insert
        // failed: deadlock detected". Either way the ticket lookup inside this one
        // delivery's transaction fails, and the point under test is that the failure
        // does not touch the other delivery.
        jdbc.update("""
                UPDATE outbox_event SET payload = jsonb_set(payload, '{ticketId}', '999999999')
                 WHERE event_type = 'INCIDENT_UPDATE_PUBLISHED'
                   AND payload @> ('{"ticketId":' || ? || '}')::jsonb
                """, badTicket);

        int processed = runtime.runAllOnce();
        assertThat(processed).isGreaterThanOrEqualTo(1);

        Integer sentCount = jdbc.queryForObject("""
                SELECT count(*) FROM incident_update_delivery d
                 JOIN incident_update u ON u.id = d.incident_update_id
                WHERE u.incident_id = ? AND d.status = 'SENT'
                """, Integer.class, incidentId);
        assertThat(sentCount).isEqualTo(1);

        Integer notifications = jdbc.queryForObject(
                "SELECT count(*) FROM notification WHERE kind = 'INCIDENT_UPDATE'", Integer.class);
        assertThat(notifications).isEqualTo(1);

        Integer notYetSent = jdbc.queryForObject("""
                SELECT count(*) FROM incident_update_delivery d
                 JOIN incident_update u ON u.id = d.incident_update_id
                WHERE u.incident_id = ? AND d.status <> 'SENT'
                """, Integer.class, incidentId);
        assertThat(notYetSent).isEqualTo(1);
    }

    private static HttpHeaders authed(String token) {
        HttpHeaders headers = AuthTestSupport.bearer(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", java.util.UUID.randomUUID().toString());
        return headers;
    }
}
