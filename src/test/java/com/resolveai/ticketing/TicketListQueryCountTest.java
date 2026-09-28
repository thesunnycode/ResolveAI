package com.resolveai.ticketing;

import static org.assertj.core.api.Assertions.assertThat;

import com.resolveai.AuthTestSupport;
import com.resolveai.IntegrationTestBase;
import com.resolveai.sla.SlaTestSupport;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The ticket list costs a fixed number of statements, however long the page is.
 *
 * <p>It used to cost eight more per row (26 statements for 2 rows, 106 for 12). Every row
 * loaded its SLA records, re-read the clock from the database, loaded each clock's
 * segments, ran up to three percentile queries for the breach prediction, looked up its
 * incident link, and lazily loaded its requester, assignee and team. That was 1.3s for a
 * page of 25 on a fresh backend and double-digit seconds on a loaded one. It is now 17
 * for any page size.
 *
 * <p>Counted at the JDBC layer, not with Hibernate statistics, because half of those reads
 * were {@code JdbcTemplate} and would not show up there. Only statements issued on the
 * request thread of a request carrying {@value #COUNT_HEADER} are counted, so the SLA
 * poller and the outbox relay running in the background cannot make this flaky.
 */
@Import(TicketListQueryCountTest.Counting.class)
class TicketListQueryCountTest extends IntegrationTestBase {

    static final String COUNT_HEADER = "X-Test-Count-Statements";

    static final ThreadLocal<Boolean> COUNTING = ThreadLocal.withInitial(() -> false);
    static final AtomicInteger STATEMENTS = new AtomicInteger();

    @TestConfiguration
    static class Counting {

        /** Wraps the pool so every prepare/create on a counted thread is tallied. */
        @Bean
        static BeanPostProcessor countingDataSource() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof DataSource ds)) {
                        return bean;
                    }
                    return Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                            new Class<?>[] {DataSource.class},
                            forward(ds, (method, result) -> method.equals("getConnection")
                                    ? wrapConnection((Connection) result) : result));
                }
            };
        }

        @Bean
        OncePerRequestFilter statementCountingFilter() {
            return new OncePerRequestFilter() {
                @Override
                protected void doFilterInternal(HttpServletRequest request,
                                                HttpServletResponse response,
                                                FilterChain chain)
                        throws ServletException, IOException {
                    boolean count = request.getHeader(COUNT_HEADER) != null;
                    COUNTING.set(count);
                    try {
                        chain.doFilter(request, response);
                    } finally {
                        COUNTING.remove();
                    }
                }
            };
        }

        private static Connection wrapConnection(Connection c) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    forward(c, (method, result) -> {
                        if (COUNTING.get() && (method.equals("prepareStatement")
                                || method.equals("createStatement")
                                || method.equals("prepareCall"))) {
                            STATEMENTS.incrementAndGet();
                        }
                        return result;
                    }));
        }

        interface After {
            Object apply(String method, Object result) throws Exception;
        }

        private static InvocationHandler forward(Object target, After after) {
            return (proxy, method, args) -> {
                if (method.getName().equals("unwrap") && args != null
                        && ((Class<?>) args[0]).isInstance(target)) {
                    return target;
                }
                if (method.getName().equals("isWrapperFor") && args != null
                        && ((Class<?>) args[0]).isInstance(target)) {
                    return true;
                }
                try {
                    return after.apply(method.getName(), method.invoke(target, args));
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            };
        }
    }

    @Autowired AuthTestSupport auth;
    @Autowired TicketTestSupport tickets;
    @Autowired SlaTestSupport sla;
    @Autowired JdbcTemplate jdbc;

    private AuthTestSupport.SeededTenant tenant;
    private String adminToken;
    private String customerToken;

    @BeforeEach
    void seed() {
        auth.wipe();
        tenant = auth.seedTenant("qcount");
        sla.makeCalendarAlwaysOpen(tenant.tenantId());
        sla.seedPolicies(tenant.tenantId(), "PRO");
        adminToken = auth.accessToken(rest, "qcount", "admin");
        customerToken = auth.accessToken(rest, "qcount", "customer");
    }

    @Test
    @DisplayName("a page of 12 tickets costs the same number of statements as a page of 2")
    void statementCountDoesNotGrowWithPageSize() {
        List<Long> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            Long id = tickets.createId(rest, customerToken, "Checkout fails " + i, "Body " + i);
            // Priorities alternate so the prediction has more than one grouping to look up.
            sla.triage(rest, tickets, adminToken, id, i % 2 == 0 ? "P2" : "P3");
            ids.add(id);
        }
        seedResolutionHistory(ids.getFirst());

        int small = countedList("size=2");
        int large = countedList("size=12");
        assertThat(large)
                .as("statements for 12 rows (%d) vs 2 rows (%d)", large, small)
                .isEqualTo(small);
        // A ceiling as well as flatness: auth, the page query, counts, last replies, the
        // ticket fetch, the SLA reads and the percentile lookups. 17 when this was written.
        assertThat(large).as("statements for a list page").isLessThanOrEqualTo(20);
    }

    @Test
    @DisplayName("SLA summaries are unchanged: clock state, remaining time and at-risk match the detail view")
    @SuppressWarnings("unchecked")
    void summariesMatchTheDetailView() {
        Long id = tickets.createId(rest, customerToken, "Refund missing", "Body");
        sla.triage(rest, tickets, adminToken, id, "P2");
        seedResolutionHistory(id);

        Map<String, Object> row = list("size=10").getFirst();
        Map<String, Object> summary = (Map<String, Object>) row.get("sla");
        Map<String, Object> detail = (Map<String, Object>) tickets.get(rest, adminToken, id)
                .getBody().get("sla");

        List<Map<String, Object>> clocks = (List<Map<String, Object>>) detail.get("clocks");
        for (Map<String, Object> clock : clocks) {
            String key = "FIRST_RESPONSE".equals(clock.get("kind")) ? "firstResponse" : "resolution";
            Map<String, Object> compact = (Map<String, Object>) summary.get(key);
            assertThat(compact.get("state")).isEqualTo(clock.get("state"));
            // Remaining can tick by a minute between the two reads.
            long listRemaining = ((Number) compact.get("remainingBusinessMinutes")).longValue();
            long detailRemaining = ((Number) clock.get("targetBusinessMinutes")).longValue()
                    - ((Number) clock.get("elapsedBusinessMinutes")).longValue();
            assertThat(listRemaining).isBetween(detailRemaining - 1, detailRemaining + 1);
            Map<String, Object> prediction = (Map<String, Object>) clock.get("prediction");
            boolean detailAtRisk = prediction != null && Boolean.TRUE.equals(prediction.get("atRisk"));
            assertThat(compact.get("atRisk")).isEqualTo(detailAtRisk);
        }
        // History says P2 takes 10,000 business minutes; the target is far shorter.
        assertThat(((Map<String, Object>) summary.get("resolution")).get("atRisk")).isEqualTo(true);
    }

    @Test
    @DisplayName("incident refs: the live confirmed link shows; proposed and detached links are hidden")
    void incidentRefsKeepTheSingleTicketRules() {
        // At most one live link per ticket (uq_incident_ticket_live); a moved ticket has a
        // detached link to its old incident and a live one to the new.
        Long moved = tickets.createId(rest, customerToken, "Moved between incidents", "Body");
        Long proposedOnly = tickets.createId(rest, customerToken, "Proposed only", "Body");
        Long detached = tickets.createId(rest, customerToken, "Detached", "Body");
        Long none = tickets.createId(rest, customerToken, "Unlinked", "Body");

        Long older = incident("INC-2000", "CONFIRMED");
        Long newer = incident("INC-2001", "MITIGATED");
        Long proposed = incident("INC-2002", "PROPOSED");
        link(older, moved, true);
        link(newer, moved, false);
        link(proposed, proposedOnly, false);
        link(older, detached, true);

        Map<Object, Object> refs = new java.util.HashMap<>();
        list("size=10").forEach(t -> refs.put(((Number) t.get("id")).longValue(), t.get("incidentRef")));

        assertThat(refs.get(moved)).isEqualTo("INC-2001");
        assertThat(refs.get(proposedOnly)).isNull();
        assertThat(refs.get(detached)).isNull();
        assertThat(refs.get(none)).isNull();
    }

    private Long incident(String reference, String status) {
        return jdbc.queryForObject("""
                INSERT INTO incident (tenant_id, reference, title, status, first_ticket_at)
                VALUES (?, ?, 'Test outage', ?, NOW()) RETURNING id
                """, Long.class, tenant.tenantId(), reference, status);
    }

    private void link(Long incidentId, Long ticketId, boolean detachedLink) {
        jdbc.update("""
                INSERT INTO incident_ticket (incident_id, ticket_id, detached_at)
                VALUES (?, ?, CASE WHEN ? THEN NOW() END)
                """, incidentId, ticketId, detachedLink);
    }

    /**
     * Enough resolved P2 history (n >= 20) that the prediction has a percentile to use, and
     * a slow one, so the resolution clock is predicted to breach.
     */
    private void seedResolutionHistory(Long anyTicketId) {
        for (int i = 0; i < 25; i++) {
            jdbc.update("""
                    INSERT INTO resolved_ticket_stat
                        (tenant_id, ticket_id, category, priority, team_id, business_minutes, resolved_at)
                    VALUES (?, ?, NULL, 'P2', NULL, 10000, NOW() - make_interval(hours => ?))
                    """, tenant.tenantId(), anyTicketId, i + 1);
        }
    }

    private int countedList(String query) {
        STATEMENTS.set(0);
        HttpHeaders headers = AuthTestSupport.bearer(adminToken);
        headers.set(COUNT_HEADER, "1");
        ResponseEntity<Map> response = rest.exchange("/api/v1/tickets?" + query, HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return STATEMENTS.get();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(String query) {
        ResponseEntity<Map> response = rest.exchange("/api/v1/tickets?" + query, HttpMethod.GET,
                new HttpEntity<>(AuthTestSupport.bearer(adminToken)), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) response.getBody().get("data");
    }
}
