package com.resolveai.demo;

import com.resolveai.iam.domain.Tenant;
import com.resolveai.iam.security.ResolvePrincipal;
import com.resolveai.platform.tenant.TenantContext;
import com.resolveai.ticketing.service.TicketService;
import com.resolveai.ticketing.web.dto.CreateTicketRequest;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * "Simulate a payment outage": {@code demo/storm.sh}, server-side, behind one button.
 *
 * <p>Posts the 38 tickets of one payment outage as the demo's customers, spread over ~20
 * seconds, so the correlation sweep (every 60s) sees a real burst and proposes an incident
 * through the ordinary statistical gate. Nothing about the incident is faked; the button
 * only replaces the shell script a reviewer would otherwise need bash, jq and curl for.
 *
 * <p>{@code classpath:demo/storm.json} is a copy of the repository's {@code demo/storm.json}
 * (the runtime classpath cannot see {@code demo/}): 38 deliberately different phrasings of
 * one outage. Detecting that burst is what {@code tau=0.68} was re-tuned for, on real
 * embeddings - see {@code CorrelationRealEmbeddingTuningTest}.
 *
 * <p><b>One storm at a time, and a cooldown after it.</b> Each storm is 38 triage calls, so
 * an unguarded button on a public demo is a budget-draining button.
 */
@Service
@ConditionalOnProperty(name = "resolveai.demo.enabled", havingValue = "true")
public class DemoStormService {

    private static final Logger log = LoggerFactory.getLogger(DemoStormService.class);
    private static final String RESOURCE = "demo/storm.json";
    private static final Duration SPREAD = Duration.ofSeconds(20);

    public enum Outcome { STARTED, ALREADY_RUNNING, COOLING_DOWN }

    public record StormStatus(boolean running, Instant lastStartedAt, Instant availableAt,
                              int ticketCount) {
    }

    private final DemoWorkspace workspace;
    private final DemoSettings settings;
    private final TicketService tickets;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<Instant> lastStartedAt = new AtomicReference<>();

    public DemoStormService(DemoWorkspace workspace, DemoSettings settings,
                            TicketService tickets, ObjectMapper objectMapper) {
        this.workspace = workspace;
        this.settings = settings;
        this.tickets = tickets;
        this.objectMapper = objectMapper;
    }

    public StormStatus status() {
        Instant last = lastStartedAt.get();
        return new StormStatus(running.get(), last,
                last == null ? null : last.plus(cooldown()), readStorm().size());
    }

    public Outcome start(Tenant tenant) {
        Instant last = lastStartedAt.get();
        if (running.get()) {
            return Outcome.ALREADY_RUNNING;
        }
        if (last != null && Instant.now().isBefore(last.plus(cooldown()))) {
            return Outcome.COOLING_DOWN;
        }
        if (!running.compareAndSet(false, true)) {
            return Outcome.ALREADY_RUNNING;
        }
        lastStartedAt.set(Instant.now());
        Thread.ofVirtual().name("demo-storm").start(() -> {
            try {
                post(tenant);
            } finally {
                running.set(false);
            }
        });
        return Outcome.STARTED;
    }

    private void post(Tenant tenant) {
        List<ResolvePrincipal> customers = workspace.customers(tenant);
        List<Map<String, String>> storm = readStorm();
        if (customers.isEmpty()) {
            log.warn("Demo storm skipped: tenant {} has no customers", tenant.getSlug());
            return;
        }
        long pauseMs = SPREAD.toMillis() / Math.max(1, storm.size());
        int posted = 0;
        for (int i = 0; i < storm.size(); i++) {
            Map<String, String> t = storm.get(i);
            ResolvePrincipal customer = customers.get(i % customers.size());
            try {
                TenantContext.callAs(tenant.getId(), () -> tickets.create(customer,
                        new CreateTicketRequest(t.get("subject"), t.get("body"), null, null)));
                posted++;
                Thread.sleep(pauseMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException e) {
                log.warn("Demo storm: ticket '{}' failed: {}", t.get("subject"), e.toString());
            }
        }
        log.info("Demo storm: posted {} of {} tickets in tenant {} — the correlation sweep "
                + "should propose an incident within about a minute", posted, storm.size(),
                tenant.getSlug());
    }

    private Duration cooldown() {
        return Duration.ofMinutes(settings.stormCooldownMinutes());
    }

    private List<Map<String, String>> readStorm() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            return objectMapper.readValue(in, new TypeReference<List<Map<String, String>>>() { });
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + RESOURCE, e);
        }
    }
}
