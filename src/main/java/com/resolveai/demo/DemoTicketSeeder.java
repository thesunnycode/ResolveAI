package com.resolveai.demo;

import com.resolveai.drafting.service.DraftRequestService;
import com.resolveai.iam.domain.Role;
import com.resolveai.iam.domain.Tenant;
import com.resolveai.iam.security.ResolvePrincipal;
import com.resolveai.platform.tenant.TenantContext;
import com.resolveai.ticketing.domain.TicketStatus;
import com.resolveai.ticketing.domain.Visibility;
import com.resolveai.ticketing.service.TicketService;
import com.resolveai.ticketing.web.dto.AddMessageRequest;
import com.resolveai.ticketing.web.dto.AssignRequest;
import com.resolveai.ticketing.web.dto.CreateTicketRequest;
import com.resolveai.ticketing.web.dto.StatusChangeRequest;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Fills an empty demo tenant with a queue worth looking at.
 *
 * <p>An empty queue on first login reads as "this does not work", and so does a wall of
 * breached tickets - the two first impressions the onboarding audit found. So the seed
 * creates a realistic mix, <b>through the real services</b>: tickets are filed by seeded
 * customers via {@link TicketService#create}, triaged by the real worker, and clocked by
 * the real SLA engine. Nothing is written around the pipeline, which is what makes the
 * demo evidence rather than a mock-up.
 *
 * <p>Three tickets are showcases, one per thing a reviewer should see (see
 * {@code demo/tickets.json}):
 * <ul>
 *   <li>{@code paused} - an agent replied (first-response clock <b>met</b>) and is waiting
 *       on the customer, so the resolution clock is <b>paused</b>;</li>
 *   <li>{@code draft} and {@code refund} - a cited draft already generated, on topics the
 *       knowledge corpus covers, so the AI rail is never empty on first visit.</li>
 * </ul>
 *
 * <p>Runs off the startup thread: triage and indexing are asynchronous, and the showcase
 * steps have to wait for them. Every step is best-effort and logged - a demo that seeds
 * 34 of 35 tickets is still a demo, and a failure here must never stop the application.
 */
@Component
@ConditionalOnProperty(name = "resolveai.demo.enabled", havingValue = "true")
public class DemoTicketSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoTicketSeeder.class);
    private static final String RESOURCE = "demo/tickets.json";

    private final DemoWorkspace workspace;
    private final TicketService tickets;
    private final DraftRequestService drafts;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public DemoTicketSeeder(DemoWorkspace workspace, TicketService tickets,
                            DraftRequestService drafts, JdbcTemplate jdbc,
                            ObjectMapper objectMapper) {
        this.workspace = workspace;
        this.tickets = tickets;
        this.drafts = drafts;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public boolean isEmpty(Tenant tenant) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM ticket WHERE tenant_id = ?",
                Long.class, tenant.getId());
        return n == null || n == 0;
    }

    /**
     * Whether a showcase ticket is missing - true on a tenant that already had tickets
     * before the demo mode existed (a long-lived local database), whose tour would
     * otherwise have nothing to point at.
     */
    public boolean isShowcaseMissing(Tenant tenant) {
        return !showcaseSpecsMissingFrom(tenant).isEmpty();
    }

    /**
     * Seeds on a virtual thread and returns immediately.
     *
     * @param showcaseOnly on a tenant that already has tickets, add only the missing
     *                     showcase tickets rather than the whole queue
     */
    public void seedInBackground(Tenant tenant, boolean showcaseOnly, Runnable afterwards) {
        Thread.ofVirtual().name("demo-seed").start(() -> {
            try {
                seed(tenant, showcaseOnly ? showcaseSpecsMissingFrom(tenant) : readTickets());
            } catch (RuntimeException e) {
                log.error("Demo seed for tenant {} failed", tenant.getSlug(), e);
            }
            if (afterwards != null) {
                afterwards.run();
            }
        });
    }

    private List<Map<String, Object>> showcaseSpecsMissingFrom(Tenant tenant) {
        return readTickets().stream()
                .filter(spec -> spec.get("showcase") != null)
                .filter(spec -> count(
                        "SELECT count(*) FROM ticket WHERE tenant_id = ? AND subject = ?",
                        tenant.getId(), spec.get("subject")) == 0)
                .toList();
    }

    void seed(Tenant tenant, List<Map<String, Object>> specs) {
        List<ResolvePrincipal> customers = workspace.customers(tenant);
        ResolvePrincipal agent = workspace.principalFor(tenant, Role.AGENT).orElse(null);
        ResolvePrincipal admin = workspace.principalFor(tenant, Role.ADMIN).orElse(null);
        if (customers.isEmpty() || agent == null || admin == null) {
            log.warn("Demo seed skipped: tenant {} lacks customers, an agent or an admin",
                    tenant.getSlug());
            return;
        }

        Map<String, Long> showcase = new LinkedHashMap<>();
        int created = 0;
        for (int i = 0; i < specs.size(); i++) {
            Map<String, Object> spec = specs.get(i);
            ResolvePrincipal customer = customers.get(i % customers.size());
            try {
                Long id = TenantContext.callAs(tenant.getId(), () -> tickets.create(customer,
                        new CreateTicketRequest((String) spec.get("subject"),
                                (String) spec.get("body"), null, null)).id());
                created++;
                if (spec.get("showcase") instanceof String key) {
                    showcase.put(key, id);
                }
            } catch (RuntimeException e) {
                log.warn("Demo seed: ticket '{}' failed: {}", spec.get("subject"), e.toString());
            }
        }
        log.info("Demo seed: {} of {} tickets created in tenant {}", created, specs.size(),
                tenant.getSlug());

        Long paused = showcase.get("paused");
        if (paused != null && awaitTriage(paused)) {
            pauseOnCustomer(tenant, paused, admin, agent);
        }

        // Drafts retrieve from the knowledge base, which indexes asynchronously - asking
        // before the corpus is embedded is exactly how a showcase draft gets suppressed.
        if (awaitIndexedCorpus(tenant)) {
            for (String key : List.of("draft", "refund")) {
                Long id = showcase.get(key);
                if (id != null && awaitTriage(id)) {
                    requestDraft(tenant, id, agent);
                }
            }
        }
    }

    private void pauseOnCustomer(Tenant tenant, Long ticketId, ResolvePrincipal admin,
                                 ResolvePrincipal agent) {
        try {
            TenantContext.runAs(tenant.getId(), () -> {
                tickets.assign(admin, ticketId, new AssignRequest(String.valueOf(agent.userId()), true));
                // A public reply meets the first-response clock, the natural reason an agent
                // would then be waiting on the customer.
                tickets.addMessage(agent, ticketId, new AddMessageRequest(
                        "Thanks for flagging this. Could you share the UPI transaction reference "
                                + "(UTR) from your bank app? I'll check the payment status with "
                                + "our payments team as soon as I have it.",
                        Visibility.PUBLIC, null, null));
                tickets.changeStatus(agent, ticketId, new StatusChangeRequest(
                        TicketStatus.WAITING_ON_CUSTOMER, "Asked the customer for the UTR"));
            });
            log.info("Demo seed: ticket {} is waiting on the customer (clock paused)", ticketId);
        } catch (RuntimeException e) {
            log.warn("Demo seed: could not pause ticket {}: {}", ticketId, e.toString());
        }
    }

    private void requestDraft(Tenant tenant, Long ticketId, ResolvePrincipal agent) {
        try {
            TenantContext.runAs(tenant.getId(), () -> drafts.request(agent, ticketId, null));
            log.info("Demo seed: draft requested for ticket {}", ticketId);
        } catch (RuntimeException e) {
            log.warn("Demo seed: draft for ticket {} failed: {}", ticketId, e.toString());
        }
    }

    /** Triage starts the SLA clocks in its own transaction, so a clock row means triaged. */
    private boolean awaitTriage(Long ticketId) {
        return await(Duration.ofSeconds(120), () -> count(
                "SELECT count(*) FROM sla_record WHERE ticket_id = ?", ticketId) > 0,
                "triage of ticket " + ticketId);
    }

    private boolean awaitIndexedCorpus(Tenant tenant) {
        return await(Duration.ofMinutes(6), () -> count("""
                SELECT count(*) FROM knowledge_document
                 WHERE tenant_id = ? AND deleted_at IS NULL AND indexed_at IS NULL
                """, tenant.getId()) == 0, "knowledge-base indexing");
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private static boolean await(Duration limit, BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + limit.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        log.warn("Demo seed: gave up waiting for {} after {}", what, limit);
        return false;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readTickets() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            Map<String, Object> root = objectMapper.readValue(in,
                    new TypeReference<Map<String, Object>>() { });
            return (List<Map<String, Object>>) root.get("tickets");
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + RESOURCE, e);
        }
    }
}
