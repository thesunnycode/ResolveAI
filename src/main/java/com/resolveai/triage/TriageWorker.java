package com.resolveai.triage;

import com.resolveai.iam.domain.PlanTier;
import com.resolveai.iam.domain.Team;
import com.resolveai.iam.repository.AppUserRepository;
import com.resolveai.iam.repository.TenantRepository;
import com.resolveai.platform.ai.AiPolicyService;
import com.resolveai.platform.ai.model.EmbeddingService;
import com.resolveai.platform.ai.model.LlmExceptions.BudgetExhaustedException;
import com.resolveai.platform.ai.model.LlmExceptions.LlmParseException;
import com.resolveai.platform.ai.model.LlmResult;
import com.resolveai.platform.ai.model.ModelRouter;
import com.resolveai.platform.ai.model.TriageSignals;
import com.resolveai.platform.ai.pii.PiiRedactor;
import com.resolveai.platform.ai.prompt.PromptVersion;
import com.resolveai.platform.ai.prompt.PromptVersionRepository;
import com.resolveai.platform.outbox.EventType;
import com.resolveai.platform.outbox.NonRetryableException;
import com.resolveai.platform.outbox.OutboxEvent;
import com.resolveai.platform.outbox.Worker;
import com.resolveai.platform.time.DatabaseClock;
import com.resolveai.ticketing.domain.Ticket;
import com.resolveai.ticketing.domain.TicketEventType;
import com.resolveai.ticketing.repository.TicketRepository;
import com.resolveai.ticketing.service.SlaLifecycle;
import com.resolveai.ticketing.service.TicketEventRecorder;
import com.resolveai.triage.policy.PolicyInputs;
import com.resolveai.triage.policy.PriorityDecision;
import com.resolveai.triage.policy.PriorityPolicy;
import com.resolveai.triage.routing.AgentAssigner;
import com.resolveai.triage.routing.RoutingPolicy;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * The assembly point: a created ticket goes in, a classified, prioritised, routed and
 * assigned ticket comes out.
 *
 * <h2>Three phases, and the middle one holds no database connection</h2>
 *
 * <pre>
 *   tx1  short read   — the ticket's text, the active prompt, the tenant's plan
 *   ---  no tx        — 2 to 8 seconds: redact, embed, classify
 *   tx2  short write  — analysis, decision, priority, routing, assignment, SLA
 * </pre>
 *
 * <p>The middle phase is the whole reason this class is shaped the way it is. A single
 * {@code @Transactional} around all of it reads perfectly well and takes the entire
 * connection pool down under ten concurrent triages — see {@code WorkerRuntime}'s class
 * comment, which explains why that failure looks like a Hikari misconfiguration and is
 * not one. {@code TransactionTemplate} rather than {@code @Transactional} because the
 * boundaries here are <i>not</i> method boundaries, and a self-invoked
 * {@code @Transactional} method silently does nothing at all.
 *
 * <h2>Failure is a first-class outcome, not an exception to escape with</h2>
 *
 * <table border="1">
 *   <caption>What each failure does</caption>
 *   <tr><th>Cause</th><th>Analysis row</th><th>Event</th></tr>
 *   <tr><td>Provider down / unreachable</td><td>none</td>
 *       <td>rethrown: retried with backoff, then dead-lettered</td></tr>
 *   <tr><td>Tenant policy forbids external models</td><td>{@code FAILED}</td>
 *       <td>done — retrying a policy cannot change it</td></tr>
 *   <tr><td>Monthly budget exhausted</td><td>{@code BUDGET_HELD}</td>
 *       <td>done — the budget will not refill inside a retry window</td></tr>
 *   <tr><td>Model output unparseable twice</td><td>{@code PARSE_FAILED}</td>
 *       <td>done — a temperature-zero model repeats itself</td></tr>
 *   <tr><td>Ticket no longer exists</td><td>none</td><td>dead-lettered immediately</td></tr>
 * </table>
 *
 * <p><b>Nothing here writes an analysis row for a transient failure</b>, and that is a
 * deliberate constraint rather than an omission. {@code uq_analysis_ticket_prompt} covers
 * {@code (ticket_id, prompt_version_id, attempt)}: a {@code FAILED} row written on
 * attempt 1 would occupy the slot that the successful retry of attempt 1 needs, so the
 * retry would insert nothing and the ticket would stay permanently unanalysed with a row
 * saying it failed. The queue already represents "still trying" — and
 * {@code AnalysisReadService} reads it — so the row is written only when there is a final
 * answer to record.
 *
 * <p>In every failure case <b>the ticket stays fully workable</b>. It keeps its default
 * team, an agent can pick it up and triage it by hand, and the fallback sweeper
 * ({@link SlaFallbackSweeper}) gives it default clocks so it cannot sit forever with no
 * SLA at all.
 */
@Component
public class TriageWorker implements Worker {

    private static final Logger log = LoggerFactory.getLogger(TriageWorker.class);

    /** The prompt this worker runs. Resolved by name, so a new version is a data change. */
    private static final String PROMPT_NAME = "triage";

    private final TransactionTemplate txTemplate;
    private final TicketRepository tickets;
    private final TenantRepository tenants;
    private final AppUserRepository users;
    private final PromptVersionRepository prompts;
    private final PiiRedactor redactor;
    private final EmbeddingService embeddings;
    private final ModelRouter models;
    private final AiPolicyService policies;
    private final PriorityPolicy priorityPolicy;
    private final RoutingPolicy routing;
    private final AgentAssigner assigner;
    private final TriageRepository triage;
    private final TicketEventRecorder eventRecorder;
    private final SlaLifecycle sla;
    private final DatabaseClock clock;
    private final ObjectMapper objectMapper;
    private final MeterRegistry metrics;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public TriageWorker(TransactionTemplate txTemplate, TicketRepository tickets,
                        TenantRepository tenants, AppUserRepository users,
                        PromptVersionRepository prompts, PiiRedactor redactor,
                        EmbeddingService embeddings, ModelRouter models,
                        AiPolicyService policies, PriorityPolicy priorityPolicy,
                        RoutingPolicy routing, AgentAssigner assigner,
                        TriageRepository triage, TicketEventRecorder eventRecorder,
                        SlaLifecycle sla, DatabaseClock clock, ObjectMapper objectMapper,
                        MeterRegistry metrics) {
        this.txTemplate = txTemplate;
        this.tickets = tickets;
        this.tenants = tenants;
        this.users = users;
        this.prompts = prompts;
        this.redactor = redactor;
        this.embeddings = embeddings;
        this.models = models;
        this.policies = policies;
        this.priorityPolicy = priorityPolicy;
        this.routing = routing;
        this.assigner = assigner;
        this.triage = triage;
        this.eventRecorder = eventRecorder;
        this.sla = sla;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    @Override
    public Set<EventType> handles() {
        return Set.of(EventType.TICKET_CREATED, EventType.TICKET_RETRIAGE_REQUESTED);
    }

    /**
     * Five minutes — longer than any plausible model call plus its one repair.
     *
     * <p>Too short and the reaper hands a still-running triage to a second worker, which
     * pays for a second model call and then loses the insert to the unique constraint:
     * money spent for nothing, and a log line that looks like a duplicate bug rather
     * than a timeout. Too long and a genuinely crashed worker's ticket waits that long
     * before anybody retries it. Five minutes against an eight-second call is the right
     * side of that trade.
     */
    @Override
    public Duration visibilityTimeout() {
        return Duration.ofMinutes(5);
    }

    /**
     * Small, because each event is seconds of wall clock.
     *
     * <p>The runtime waits for a whole batch before polling again, so a large batch makes
     * the slowest event in it delay everything behind it. Five in flight at a time keeps
     * the pipeline moving and keeps the blast radius of one stuck provider call small.
     */
    @Override
    public int batchSize() {
        return 5;
    }

    @Override
    public void process(OutboxEvent event) {
        Payload payload = readPayload(event);

        // ── tx1: short read ─────────────────────────────────────────────────
        Context context = txTemplate.execute(status -> load(event, payload));
        if (context == null) {
            // The ticket was deleted between publish and claim. Retrying cannot make it
            // exist, so this goes straight to the dead-letter queue rather than burning
            // five attempts discovering the same thing.
            throw new NonRetryableException(
                    "Ticket " + payload.ticketId() + " no longer exists");
        }

        if (!context.aiAllowed()) {
            // Checked here rather than relying on the router's refusal so the outcome is
            // recorded as a final state instead of an exception. The tenant turned AI
            // off; that is a configuration answer, not a failure to retry.
            log.info("Tenant {} does not permit external models; recording ticket {} as "
                     + "requiring manual triage", event.tenantId(), context.ticketId());
            recordTerminalFailure(context, "FAILED");
            return;
        }

        // ── no transaction: the network ─────────────────────────────────────
        String text = context.subject() + "\n\n" + context.body();
        PiiRedactor.RedactionResult redacted =
                redactor.redact(context.tenantId(), context.ticketId(), text);

        LlmResult<TriageSignals> result;
        float[] embedding;
        try {
            // The embedding is computed from the same redacted text the model sees, so
            // the content-hash cache key and the classification agree by construction.
            embedding = embeddings.embed(context.tenantId(), redacted.redactedText());
            result = models.call(context.tenantId(), context.prompt(),
                    redacted.redactedText(), TriageSignals.class);
        } catch (BudgetExhaustedException e) {
            log.warn("Tenant {} has exhausted its AI budget; ticket {} needs manual triage",
                    context.tenantId(), context.ticketId());
            recordTerminalFailure(context, "BUDGET_HELD");
            return;
        } catch (LlmParseException e) {
            // The model answered, twice, and neither answer was usable. Retrying a
            // temperature-zero model produces the same output at the same price.
            log.warn("Triage of ticket {} could not be parsed; recording PARSE_FAILED",
                    context.ticketId(), e);
            recordTerminalFailure(context, "PARSE_FAILED");
            return;
        }

        // ── tx2: short write ────────────────────────────────────────────────
        txTemplate.executeWithoutResult(status ->
                persistAnalysisAndDecide(context, result, embedding));
    }

    /**
     * Everything the network phase needs, read in one short transaction.
     *
     * <p>Returned as a detached record rather than as entities. An entity read here and
     * used after the transaction closes is a lazy-loading exception waiting for the first
     * field nobody thought about, and the eight seconds in between is plenty of time for
     * the row to have changed anyway.
     */
    private Context load(OutboxEvent event, Payload payload) {
        Optional<Ticket> found = tickets.findById(payload.ticketId());
        if (found.isEmpty()) {
            return null;
        }
        Ticket ticket = found.get();

        PromptVersion prompt = prompts.findByNameAndActiveTrue(PROMPT_NAME)
                .orElseThrow(() -> new NonRetryableException(
                        "No active prompt named " + PROMPT_NAME
                        + "; V10 should have seeded one"));

        boolean aiAllowed = policies.check(event.tenantId()).externalModelAllowed();

        return new Context(event.tenantId(), ticket.getId(), ticket.getSubject(),
                ticket.getBody(), prompt, payload.attempt(), aiAllowed);
    }

    /**
     * The write phase: record what happened, decide, route, assign, start the clocks.
     *
     * <p>One transaction for all of it, on purpose. A ticket with a priority but no SLA
     * record, or an assignment without the capacity increment that pays for it, are both
     * states nothing would ever repair — and both are what a series of small
     * transactions here would eventually produce.
     */
    private void persistAnalysisAndDecide(Context context, LlmResult<TriageSignals> result,
                                          float[] embedding) {
        TriageSignals signals = result.value();

        Optional<Long> analysisId = triage.insertAnalysis(
                context.tenantId(), context.ticketId(), context.prompt().getId(),
                result.modelId(), objectMapper.writeValueAsString(signals),
                signals.confidence(), result.tokensIn(), result.tokensOut(),
                result.costMicros(), (int) result.latencyMs(), context.attempt(), "OK");

        if (analysisId.isEmpty()) {
            // A redelivery of an event whose first run had already committed this row.
            // The desired end state is already true, so this is an ordinary success.
            log.info("Ticket {} attempt {} was already analysed; nothing to do",
                    context.ticketId(), context.attempt());
            metrics.counter("triage.duplicate").increment();
            return;
        }

        Ticket ticket = tickets.findById(context.ticketId()).orElseThrow(
                () -> new NonRetryableException("Ticket " + context.ticketId()
                                                + " vanished mid-triage"));

        PlanTier planTier = tenants.findById(context.tenantId())
                .map(t -> t.getPlanTier()).orElse(PlanTier.FREE);

        // Phase 8 supplies a linked incident's priority here. Until then it is null, and
        // the rule reports itself as "not linked to a live incident" in every rationale.
        PolicyInputs inputs = new PolicyInputs(signals, planTier, null,
                ticket.getReopenCount());
        PriorityDecision decision = priorityPolicy.evaluate(inputs);

        // The rationale column carries the trace AND the sentence generated from it. The
        // sentence could be regenerated on read, and must not be: regenerating it would
        // describe a decision made under v1 in the words of whatever version is deployed
        // when somebody opens the ticket.
        triage.insertDecision(context.tenantId(), context.ticketId(), analysisId.get(),
                decision.policyVersion(), objectMapper.writeValueAsString(
                        inputSnapshot(signals, planTier, ticket.getReopenCount())),
                decision.priority(), objectMapper.writeValueAsString(
                        Map.of("rules", decision.rules(),
                               "humanReadable", decision.humanReadable())));

        var previousPriority = ticket.getPriority();
        ticket.setPriority(decision.priority());
        ticket.setCategory(signals.category().name());

        // Routing and assignment, in that order: an agent is chosen from the owning
        // team, so the team has to be settled first.
        Optional<Team> team = routing.selectTeam(context.tenantId(), signals.category().name());
        team.ifPresent(ticket::setTeam);

        Optional<Long> assignee = assigner.claim(context.tenantId(),
                team.map(Team::getId).orElse(null), clock.now());
        assignee.flatMap(users::findById).ifPresent(ticket::setAssignee);

        eventRecorder.record(ticket, TicketEventType.TRIAGED, previousPriority.name(),
                decision.priority().name(),
                Map.of("category", signals.category().name(),
                        "modelId", result.modelId(),
                        "promptVersion", context.prompt().label(),
                        "policyVersion", decision.policyVersion(),
                        "confidence", signals.confidence(),
                        "team", team.map(Team::getName).orElse("UNROUTED")));
        assignee.ifPresent(userId -> eventRecorder.record(ticket, TicketEventType.ASSIGNED,
                null, String.valueOf(userId), Map.of("source", "AUTO_ROUTING")));

        // ── Task 28: the clocks start here, not at creation ─────────────────
        //
        // A clock measures a promise, and the promise is not known until the priority
        // is. Starting at creation would measure every ticket against a default target
        // and then quietly keep measuring against it after triage decided otherwise —
        // a P1 tracked on a P3 deadline, reported as comfortably within SLA.
        sla.start(ticket);

        tickets.saveAndFlush(ticket);
        embeddings.storeTicketEmbedding(context.ticketId(), embedding);

        metrics.counter("triage.completed", "priority", decision.priority().name(),
                "category", signals.category().name()).increment();
        log.info("Ticket {} triaged as {} {} ({}), team {}, assignee {}",
                ticket.getReference(), signals.category(), decision.priority(),
                decision.policyVersion(), team.map(Team::getName).orElse("UNROUTED"),
                assignee.map(String::valueOf).orElse("none"));
    }

    /**
     * Records a final, non-retryable failure and leaves the ticket for a human.
     *
     * <p>No priority is computed. Guessing one from no signals would put a number on the
     * ticket that looks like a decision and is not, and the agent who sees "P3" has no
     * way to know nothing decided it. {@link SlaFallbackSweeper} gives the ticket default
     * clocks so it is still tracked; the analysis endpoint says {@code UNAVAILABLE} with
     * {@code manualTriageRequired}.
     */
    private void recordTerminalFailure(Context context, String status) {
        txTemplate.executeWithoutResult(status0 -> triage.insertAnalysis(
                context.tenantId(), context.ticketId(), context.prompt().getId(),
                context.prompt().getModelId(), "{}", null, 0, 0, 0L, 0,
                context.attempt(), status));
        metrics.counter("triage.failed", "status", status).increment();
    }

    /**
     * The JSONB snapshot stored on the decision.
     *
     * <p><b>Every input, model and system alike</b>, and kept in two labelled groups. The
     * rationale endpoint renders the split, and it is the split that tells an agent
     * whether an override means "the model misread this" or "the policy is wrong about
     * tickets like this" — two different bugs with two different fixes.
     *
     * <p>Snapshotted rather than re-read on display, because the plan tier and the reopen
     * count both change, and a rationale that re-reads them would explain a past decision
     * with present facts.
     */
    private static Map<String, Object> inputSnapshot(TriageSignals signals, PlanTier planTier,
                                                     int reopenCount) {
        Map<String, Object> fromModel = new LinkedHashMap<>();
        fromModel.put("category", signals.category().name());
        fromModel.put("reportedImpact", signals.reportedImpact().name());
        fromModel.put("serviceDownClaimed", signals.serviceDownClaimed());
        fromModel.put("dataLossClaimed", signals.dataLossClaimed());
        fromModel.put("paymentAffected", signals.paymentAffected());
        fromModel.put("linguisticUrgency", signals.linguisticUrgency().name());
        fromModel.put("confidence", signals.confidence());

        Map<String, Object> fromSystem = new LinkedHashMap<>();
        fromSystem.put("planTier", planTier.name());
        fromSystem.put("linkedIncident", null);
        fromSystem.put("reopenCount", reopenCount);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("fromModel", fromModel);
        snapshot.put("fromSystem", fromSystem);
        return snapshot;
    }

    /**
     * The event body.
     *
     * <p>{@code attempt} defaults to 1 so a {@code TICKET_CREATED} written by Phase 6A,
     * which had no concept of attempts, still processes. A payload that cannot be read at
     * all is non-retryable: it will not parse better in thirty seconds.
     */
    @SuppressWarnings("unchecked")
    private Payload readPayload(OutboxEvent event) {
        try {
            Map<String, Object> body = objectMapper.readValue(event.payload(), Map.class);
            Object ticketId = body.get("ticketId");
            if (ticketId == null) {
                // Fall back to the aggregate id, which the outbox always carries.
                return new Payload(event.aggregateId(), attemptOf(body));
            }
            return new Payload(Long.valueOf(String.valueOf(ticketId)), attemptOf(body));
        } catch (RuntimeException e) {
            throw new NonRetryableException(
                    "Unreadable payload on outbox event " + event.id(), e);
        }
    }

    private static int attemptOf(Map<String, Object> body) {
        Object attempt = body.get("attempt");
        return attempt == null ? 1 : Integer.parseInt(String.valueOf(attempt));
    }

    private record Payload(Long ticketId, int attempt) {
    }

    /** Everything carried across the transaction gap, detached and immutable. */
    private record Context(Long tenantId, Long ticketId, String subject, String body,
                           PromptVersion prompt, int attempt, boolean aiAllowed) {
    }
}
