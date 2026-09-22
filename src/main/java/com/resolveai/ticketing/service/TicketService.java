package com.resolveai.ticketing.service;

import com.resolveai.common.error.ApiException;
import com.resolveai.common.error.ErrorCode;
import com.resolveai.common.pagination.CursorPage;
import com.resolveai.iam.domain.AgentProfile;
import com.resolveai.iam.domain.AppUser;
import com.resolveai.iam.domain.Role;
import com.resolveai.iam.domain.Team;
import com.resolveai.iam.repository.AgentProfileRepository;
import com.resolveai.iam.repository.AppUserRepository;
import com.resolveai.iam.repository.TeamRepository;
import com.resolveai.iam.security.ResolvePrincipal;
import com.resolveai.platform.outbox.EventType;
import com.resolveai.platform.outbox.OutboxPublisher;
import com.resolveai.platform.sequence.ReferenceGenerator;
import com.resolveai.ticketing.domain.Priority;
import com.resolveai.ticketing.domain.SlaEffect;
import com.resolveai.ticketing.domain.Ticket;
import com.resolveai.ticketing.domain.TicketEventType;
import com.resolveai.ticketing.domain.TicketMessage;
import com.resolveai.ticketing.domain.TicketStateMachine;
import com.resolveai.ticketing.domain.TicketStatus;
import com.resolveai.ticketing.domain.Visibility;
import com.resolveai.ticketing.repository.TicketMessageRepository;
import com.resolveai.ticketing.repository.TicketQueryRepository;
import com.resolveai.ticketing.repository.TicketRepository;
import com.resolveai.ticketing.web.dto.AddMessageRequest;
import com.resolveai.ticketing.web.dto.AssignRequest;
import com.resolveai.ticketing.web.dto.CreateTicketRequest;
import com.resolveai.ticketing.web.dto.MessageResponse;
import com.resolveai.ticketing.web.dto.ReopenRequest;
import com.resolveai.ticketing.web.dto.ResolveRequest;
import com.resolveai.ticketing.web.dto.StatusChangeRequest;
import com.resolveai.ticketing.web.dto.StatusChangeResponse;
import com.resolveai.ticketing.web.dto.TicketSummaryResponse;
import com.resolveai.ticketing.web.dto.UpdateTicketRequest;
import com.resolveai.platform.time.DatabaseClock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ticket lifecycle.
 *
 * <p>Everything a ticket can have done to it lives here, and every method is one
 * transaction: the business change, its audit event and its SLA effect commit together or
 * not at all. That is not tidiness — a status change that commits while its clock pause
 * rolls back leaves an SLA that is permanently wrong and silently so.
 *
 * <p>The state machine is consulted, never duplicated. There is no
 * {@code if (status == CLOSED)} in this file except where {@code CLOSED} means something
 * other than a transition rule.
 */
@Service
public class TicketService {

    private static final Logger log = LoggerFactory.getLogger(TicketService.class);

    private final TicketRepository tickets;
    private final TicketMessageRepository messages;
    private final TicketQueryRepository ticketQuery;
    private final AppUserRepository users;
    private final TeamRepository teams;
    private final AgentProfileRepository agentProfiles;
    private final ReferenceGenerator references;
    private final TicketEventRecorder eventRecorder;
    private final TicketAccess access;
    private final TicketMapper mapper;
    private final SlaLifecycle sla;
    private final DatabaseClock clock;
    private final OutboxPublisher outbox;
    private final PriorityOverrideRecorder priorityOverrides;
    private final DraftReferenceValidator draftReferences;

    public TicketService(TicketRepository tickets, TicketMessageRepository messages,
                         TicketQueryRepository ticketQuery, AppUserRepository users,
                         TeamRepository teams, AgentProfileRepository agentProfiles,
                         ReferenceGenerator references, TicketEventRecorder eventRecorder,
                         TicketAccess access, TicketMapper mapper, SlaLifecycle sla,
                         DatabaseClock clock, OutboxPublisher outbox,
                         PriorityOverrideRecorder priorityOverrides,
                         DraftReferenceValidator draftReferences) {
        this.tickets = tickets;
        this.messages = messages;
        this.ticketQuery = ticketQuery;
        this.users = users;
        this.teams = teams;
        this.agentProfiles = agentProfiles;
        this.references = references;
        this.eventRecorder = eventRecorder;
        this.access = access;
        this.mapper = mapper;
        this.sla = sla;
        this.clock = clock;
        this.outbox = outbox;
        this.priorityOverrides = priorityOverrides;
        this.draftReferences = draftReferences;
    }

    // ── Create ──────────────────────────────────────────────────────────────

    /**
     * Creates a ticket and queues its triage, in one transaction.
     *
     * <p><b>{@code 202} from Phase 6, where Phase 5 returned {@code 201}.</b> The resource
     * is created, which normally argues for {@code 201} — but {@code priority},
     * {@code category}, {@code assignee} and {@code team} are all still empty and
     * <i>will change with no further client action</i>. {@code 201} claims the
     * representation is final; {@code 202} says the work has been accepted and points at
     * where to watch it. The status code follows the semantics, and the semantics changed.
     *
     * <p><b>The ticket and its outbox event commit together.</b> That single transaction
     * is the whole reason the outbox exists: there is no window where a ticket exists
     * without a triage job, and none where a job exists for a ticket that rolled back.
     * See {@link com.resolveai.platform.outbox.OutboxPublisher}.
     */
    @Transactional
    public TicketSummaryResponse create(ResolvePrincipal principal, CreateTicketRequest request) {
        AppUser caller = access.caller(principal);
        AppUser requester = resolveRequester(principal, caller, request.onBehalfOf());

        Ticket ticket = new Ticket(
                references.next(ReferenceGenerator.EntityType.TICKET),
                request.subject().trim(), request.body(), requester);
        // The default team, so the ticket is visible to somebody from the moment it exists.
        // Without it an unrouted ticket is invisible to every agent and every team lead,
        // and only an admin would ever find it.
        teams.findByIsDefaultTrue().ifPresent(ticket::setTeam);
        tickets.save(ticket);

        eventRecorder.record(ticket, TicketEventType.CREATED, null,
                TicketStatus.OPEN.name(),
                Map.of("reference", ticket.getReference(),
                        "onBehalfOf", request.onBehalfOf() != null));

        // The triage job, in this transaction. Its payload is identifying rather than
        // descriptive - the worker re-reads the ticket, because by the time it runs the
        // subject may have been edited and a copy in the payload would be stale.
        outbox.publish("TICKET", ticket.getId(), EventType.TICKET_CREATED,
                Map.of("ticketId", ticket.getId(), "reference", ticket.getReference()));

        // sla.start() deliberately does NOT happen here any more. A clock measures a
        // promise, and the promise is not known until the priority is - so the clocks
        // start inside the triage transaction instead. The gap between creation and
        // triage is a few seconds in which a ticket has no SLA; that is a deliberate
        // consequence of the promise being unknown, not a hole.

        if (request.attachmentIds() != null && !request.attachmentIds().isEmpty()) {
            // Attachments are Task 17, which was cut. Rejecting is the honest answer:
            // silently dropping them would have the client believe the file was attached.
            throw new ApiException(ErrorCode.ATTACHMENT_NOT_FOUND,
                    "Attachments are not available in this build.");
        }

        log.info("Ticket {} created by user {}", ticket.getReference(), principal.userId());
        return mapper.toSummary(ticket, 0L);
    }

    /**
     * Who the ticket is <i>for</i>.
     *
     * <p>{@code onBehalfOf} is how an agent files a ticket from a phone call. A
     * {@code CUSTOMER} sending it gets {@code 403} — otherwise any customer could attribute
     * a ticket to any other, and the requester is what the visibility rule keys on.
     */
    private AppUser resolveRequester(ResolvePrincipal principal, AppUser caller, Long onBehalfOf) {
        if (onBehalfOf == null) {
            return caller;
        }
        if (!principal.isAtLeast(Role.AGENT)) {
            throw ApiException.forbidden("Only agents and above may raise a ticket on behalf "
                    + "of someone else.");
        }
        return users.findById(onBehalfOf)
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND,
                        "User " + onBehalfOf + " was not found."));
    }

    // ── Read ────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public CursorPage<TicketSummaryResponse> list(ResolvePrincipal principal,
                                                  TicketQueryRepository.Filters filters) {
        Long callerTeamId = access.teamIdOf(principal);
        List<Long> ids = ticketQuery.findPageIds(principal, callerTeamId, filters);
        if (ids.isEmpty()) {
            return CursorPage.empty(filters.size());
        }

        Map<Long, Long> counts = ticketQuery.messageCounts(ids, principal.tenantId());
        // findAllById does not preserve order; the page order came from the SQL and the
        // cursor depends on the last row being the last row.
        Map<Long, Ticket> byId = new LinkedHashMap<>();
        tickets.findAllById(ids).forEach(t -> byId.put(t.getId(), t));
        List<Ticket> ordered = ids.stream().map(byId::get).filter(Objects::nonNull).toList();

        return CursorPage.of(ordered, filters.size(),
                        t -> new com.resolveai.common.pagination.Cursor(t.getCreatedAt(), t.getId()))
                .map(t -> mapper.toSummary(t, counts.getOrDefault(t.getId(), 0L)));
    }

    /**
     * The detail view. Returns {@code TicketDetailResponse} or
     * {@code TicketCustomerResponse} — <b>two types, selected by role.</b> See
     * {@code TicketCustomerResponse} for why that is not one type with conditional nulls.
     */
    @Transactional(readOnly = true)
    public Object detail(ResolvePrincipal principal, Long ticketId, boolean includeTimeline) {
        Ticket ticket = access.loadVisible(principal, ticketId);
        return principal.role() == Role.CUSTOMER
                ? mapper.toCustomerDetail(ticket)
                : mapper.toAgentDetail(ticket, includeTimeline);
    }

    // ── Update ──────────────────────────────────────────────────────────────

    /** Subject, category and team. Nothing else — see {@code UpdateTicketRequest}. */
    @Transactional
    public Object update(ResolvePrincipal principal, Long ticketId,
                         UpdateTicketRequest request) {
        access.requireAgentOrAbove(principal, "edit a ticket");
        Ticket ticket = access.loadVisibleForUpdate(principal, ticketId);
        requireNotClosed(ticket);

        // Each branch runs only when the client actually mentioned the field. An absent
        // field leaves the value alone; an explicit null clears it. See UpdateTicketRequest
        // for why presence is tracked in the setters rather than with Optional.
        if (request.hasSubject()) {
            if (request.getSubject() == null || request.getSubject().isBlank()) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "subject cannot be cleared.");
            }
            recordFieldChange(ticket, "subject", ticket.getSubject(), request.getSubject());
            ticket.setSubject(request.getSubject().trim());
        }
        if (request.hasCategory()) {
            recordFieldChange(ticket, "category", ticket.getCategory(), request.getCategory());
            ticket.setCategory(request.getCategory());
        }
        if (request.hasTeamId()) {
            Long nextId = request.getTeamId();
            Team next = nextId == null ? null : teams.findById(nextId)
                    .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_ERROR,
                            "Team " + nextId + " was not found in this tenant."));
            recordFieldChange(ticket, "team",
                    ticket.getTeam() == null ? null : ticket.getTeam().getName(),
                    next == null ? null : next.getName());
            ticket.setTeam(next);
        }

        tickets.saveAndFlush(ticket);
        return mapper.toAgentDetail(ticket, false);
    }

    private void recordFieldChange(Ticket ticket, String field, String from, String to) {
        if (Objects.equals(from, to)) {
            return;
        }
        eventRecorder.record(ticket, TicketEventType.STATUS_CHANGED, from, to,
                Map.of("field", field));
    }

    /**
     * Sets a ticket's priority by hand, with a reason.
     *
     * <p><b>Priority is computed, never set</b> — doc 05 §STEP 2 is explicit that there is
     * no {@code POST /tickets/{id}/priority}. This is the one human path, and it requires
     * a reason precisely so that the override can be analysed later: the question worth
     * answering is whether the model misread the ticket or the policy is wrong, and those
     * are two completely different bugs that look identical without the reason.
     *
     * <p><b>In Phase 5 this is also the only way a ticket gets a priority at all</b>, and
     * therefore the only way its SLA clocks start. Phase 6's triage worker computes one
     * automatically; until then an agent decides, which is what they would do anyway when
     * triage is down. Starting the clocks here rather than at creation is the arrangement
     * the plan says Phase 6 moves to, so this path does not change when triage arrives.
     */
    @Transactional
    public TicketSummaryResponse overridePriority(
            ResolvePrincipal principal, Long ticketId,
            com.resolveai.ticketing.web.dto.PriorityOverrideRequest request) {
        access.requireAgentOrAbove(principal, "set a ticket's priority");
        Ticket ticket = access.loadVisibleForUpdate(principal, ticketId);
        requireNotClosed(ticket);

        Priority from = ticket.getPriority();
        ticket.setPriority(request.priority());
        eventRecorder.record(ticket, TicketEventType.PRIORITY_CHANGED, from.name(),
                request.priority().name(),
                Map.of("reason", request.reason(), "source", "HUMAN_OVERRIDE"));

        // The override row, separate from the timeline event. It exists because it is
        // training data: "enterprise customer, contract says P1 for any payment issue"
        // is a label, and a table of labels paired with the model signals that produced
        // the original decision is the only honest way to tell later whether the model
        // is misreading tickets or the policy is wrong. The timeline event is for
        // humans reading one ticket; this is for querying across thousands.
        priorityOverrides.record(principal.tenantId(), ticketId, from, request.priority(),
                request.reason(), principal.userId());

        // Two calls, because the ticket may be in either of two states. An untriaged
        // ticket has no clocks at all, so start() creates them against the new priority;
        // an already-triaged one has running clocks pointed at the OLD target, and
        // leaving them there would mean a P3 escalated to P1 keeps being judged on a P3
        // deadline - within SLA right up to the point somebody notices. start() is
        // idempotent, so calling both is safe in either state.
        sla.start(ticket);
        sla.retarget(ticket);

        tickets.saveAndFlush(ticket);
        return mapper.toSummary(ticket, messages.countByTicketId(ticketId));
    }

    // ── Messages ────────────────────────────────────────────────────────────

    /**
     * Adds a message, and — if it is an agent's first public reply — stops the
     * first-response clock in the same transaction.
     *
     * <p>That single transaction is what closes the reply-versus-breach race. An agent
     * replying at the exact instant a breach would fire cannot produce both a first
     * response and a breach, because the poller's per-record {@code SELECT … FOR UPDATE}
     * and this transaction serialise against the same row.
     */
    @Transactional
    public MessageResponse addMessage(ResolvePrincipal principal, Long ticketId,
                                      AddMessageRequest request) {
        Ticket ticket = access.loadVisibleForUpdate(principal, ticketId);
        if (ticket.isClosed()) {
            throw new ApiException(ErrorCode.TICKET_CLOSED,
                    "Ticket " + ticket.getReference() + " is closed. Reopen it to reply.");
        }

        Visibility visibility = request.visibilityOrDefault();
        if (visibility == Visibility.INTERNAL && !principal.isAtLeast(Role.AGENT)) {
            throw ApiException.forbidden("Only agents and above may add internal notes.");
        }
        if (request.attachmentIds() != null && !request.attachmentIds().isEmpty()) {
            throw new ApiException(ErrorCode.ATTACHMENT_NOT_FOUND,
                    "Attachments are not available in this build.");
        }
        // Doc 15 Task 21: fromDraftId was accepted and stored unvalidated since Phase 5.
        // Validated first, before anything is written, so an invalid reference is a 422
        // with nothing persisted rather than a message saved and then found to be wrong.
        if (request.fromDraftId() != null) {
            draftReferences.requireValid(ticketId, request.fromDraftId());
        }

        AppUser author = access.caller(principal);
        OffsetDateTime now = clock.now();

        // Three conditions, all necessary. Public, because an internal note is not a reply
        // to the customer. Not the requester, because a customer adding detail to their own
        // ticket has not been responded to. And not already responded, because "first"
        // means first.
        boolean isFirst = visibility == Visibility.PUBLIC
                && !Objects.equals(author.getId(), ticket.getRequester().getId())
                && ticket.getFirstRespondedAt() == null;

        TicketMessage message = new TicketMessage(ticket, author, request.body(), visibility,
                isFirst, request.fromDraftId());

        MessageResponse.SlaEffectResponse effect = null;
        try {
            messages.saveAndFlush(message);
        } catch (DataIntegrityViolationException e) {
            // idx_message_first_response is a partial unique index on
            // (ticket_id) WHERE is_first_response. Two agents replying in the same
            // millisecond both computed isFirst = true; this one lost. Its message is still
            // a perfectly good message — retry it as an ordinary reply rather than failing
            // a request that did nothing wrong.
            if (!isFirst) {
                throw e;
            }
            log.debug("Lost the first-response race on ticket {}; saving as a normal reply",
                    ticket.getId());
            message = new TicketMessage(ticket, author, request.body(), visibility, false,
                    request.fromDraftId());
            messages.saveAndFlush(message);
            isFirst = false;
        }

        if (isFirst) {
            ticket.setFirstRespondedAt(now);
            var outcome = sla.markFirstResponseMet(ticket, now);
            if (outcome != null) {
                effect = new MessageResponse.SlaEffectResponse(outcome.state(), outcome.at());
            }
        }

        eventRecorder.record(ticket, TicketEventType.MESSAGE_ADDED, null,
                visibility.name(),
                Map.of("messageId", message.getId(), "isFirstResponse", isFirst));
        tickets.saveAndFlush(ticket);

        // Automatic capture is what makes SENT_AS_IS / EDITED rates trustworthy: relying
        // on the frontend to call POST /drafts/{id}/action separately means it gets
        // missed on the common path (an agent just hits "send"), and a rate computed
        // from partial data is worse than no rate at all. Same transaction as the
        // message, so a crash between the two cannot leave one without the other.
        if (request.fromDraftId() != null) {
            draftReferences.recordAutomaticAction(request.fromDraftId(), author.getId(),
                    request.body());
        }

        return MessageResponse.of(message, effect);
    }

    // ── Assignment ──────────────────────────────────────────────────────────

    /**
     * Assigns a ticket. <b>The endpoint with the concurrency test behind it.</b>
     *
     * <p>The claim is a conditional {@code UPDATE … WHERE assignee_id IS NULL} with an
     * affected-row check, not a read followed by a write. A read-then-write has a window
     * between the two statements in which another request can win, and both callers then
     * believe they succeeded. The conditional update has no window, because the predicate
     * is evaluated under the row lock the {@code UPDATE} itself takes.
     *
     * <p>Twenty concurrent requests therefore produce exactly one {@code 200} and nineteen
     * {@code 409 ALREADY_ASSIGNED}. {@code ConcurrentAssignmentTest} asserts that
     * distribution.
     */
    @Transactional
    public TicketSummaryResponse assign(ResolvePrincipal principal, Long ticketId,
                                        AssignRequest request) {
        access.requireAgentOrAbove(principal, "assign a ticket");
        Ticket ticket = access.loadVisible(principal, ticketId);
        requireNotClosed(ticket);

        Long targetId = request.isSelfAssign()
                ? principal.userId() : Long.valueOf(request.assigneeId());

        // An AGENT may take work, not hand it out. Only a lead decides who does what.
        if (!Objects.equals(targetId, principal.userId()) && !principal.isAtLeast(Role.TEAM_LEAD)) {
            throw ApiException.forbidden("An agent may only assign a ticket to themselves.");
        }
        if (request.forced() && !principal.isAtLeast(Role.TEAM_LEAD)) {
            throw ApiException.forbidden("Only a team lead or above may reassign a ticket that "
                    + "is already assigned.");
        }

        AppUser target = users.findById(targetId)
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND,
                        "User " + targetId + " was not found."));
        AgentProfile profile = agentProfiles.findByUserIdForUpdate(targetId)
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND,
                        "User " + targetId + " has no agent profile and cannot take tickets."));
        if (!profile.hasCapacity()) {
            throw new ApiException(ErrorCode.AGENT_AT_CAPACITY,
                    profile.isAvailable()
                            ? target.getFullName() + " is at capacity ("
                              + profile.getOpenCount() + "/" + profile.getMaxConcurrent() + ")."
                            : target.getFullName() + " is not available.");
        }

        Long previousAssignee = ticket.getAssignee() == null ? null : ticket.getAssignee().getId();
        int updated = request.forced()
                ? tickets.reassign(ticketId, targetId, principal.tenantId())
                : tickets.assignIfUnassigned(ticketId, targetId, principal.tenantId());

        if (updated == 0) {
            // Not an error in the system — the system worked. Somebody else got there first.
            throw new ApiException(ErrorCode.ALREADY_ASSIGNED,
                    "This ticket was assigned to someone else. Reload to see who.");
        }

        agentProfiles.incrementOpenCount(targetId, principal.tenantId());
        if (previousAssignee != null && !Objects.equals(previousAssignee, targetId)) {
            agentProfiles.decrementOpenCount(previousAssignee, principal.tenantId());
        }

        // The conditional UPDATE was native, so the entity in this persistence context is
        // stale by one version. Re-read rather than patch it by hand: the status may also
        // have moved, and guessing which fields the statement touched is how the two drift.
        Ticket reloaded = tickets.findById(ticketId).orElseThrow();
        eventRecorder.record(reloaded, TicketEventType.ASSIGNED,
                previousAssignee == null ? null : String.valueOf(previousAssignee),
                String.valueOf(targetId),
                Map.of("forced", request.forced()));

        return mapper.toSummary(reloaded, messages.countByTicketId(ticketId));
    }

    // ── State machine ───────────────────────────────────────────────────────

    /**
     * Drives the state machine. Every illegal move is a {@code 409} carrying the permitted
     * targets, so a client can recover without a copy of the transition table.
     */
    @Transactional
    public StatusChangeResponse changeStatus(ResolvePrincipal principal, Long ticketId,
                                             StatusChangeRequest request) {
        access.requireAgentOrAbove(principal, "change a ticket's status");
        Ticket ticket = access.loadVisibleForUpdate(principal, ticketId);

        TicketStatus from = ticket.getStatus();
        TicketStatus to = request.status();
        if (!TicketStateMachine.canTransition(from, to)) {
            throw new IllegalTransitionException(from, to);
        }
        if (to.isWaiting() && (request.reason() == null || request.reason().isBlank())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "A reason is required when moving a ticket to " + to + ".");
        }

        ticket.moveTo(to, clock.now());
        Map<String, Object> effect = applySlaEffect(ticket, from, to, request.reason());
        eventRecorder.recordStatusChange(ticket, from.name(), to.name(), request.reason());
        tickets.saveAndFlush(ticket);

        return new StatusChangeResponse(ticket.getId(), ticket.getStatus(), effect,
                EtagSupport.etagOf(ticket.getVersion()));
    }

    /**
     * Applies the clock effect the state machine derived.
     *
     * <p><b>Driven entirely from {@code sideEffectOf}.</b> There is no
     * {@code if (to == WAITING_ON_CUSTOMER)} here, which is the point: a ninth status added
     * to the enum cannot arrive with no clock behaviour, because the table and the effect
     * are the same table.
     */
    private Map<String, Object> applySlaEffect(Ticket ticket, TicketStatus from,
                                               TicketStatus to, String reason) {
        SlaEffect effect = TicketStateMachine.sideEffectOf(from, to);
        return switch (effect) {
            case PAUSE -> sla.pauseResolution(ticket, to.name());
            case RESUME -> sla.resumeResolution(ticket);
            case STOP -> sla.stopResolution(ticket);
            case NONE -> Map.of();
        };
    }

    @Transactional
    public StatusChangeResponse resolve(ResolvePrincipal principal, Long ticketId,
                                        ResolveRequest request) {
        access.requireAgentOrAbove(principal, "resolve a ticket");
        Ticket ticket = access.loadVisibleForUpdate(principal, ticketId);

        TicketStatus from = ticket.getStatus();
        if (!TicketStateMachine.canTransition(from, TicketStatus.RESOLVED)) {
            throw new IllegalTransitionException(from, TicketStatus.RESOLVED);
        }

        OffsetDateTime now = clock.now();
        AppUser author = access.caller(principal);

        // The resolution is stored as the final public message rather than as a column: it
        // is something an agent wrote to a customer, it belongs in the thread the customer
        // reads, and Phase 7 indexes the thread for the knowledge base.
        TicketMessage closing = new TicketMessage(ticket, author, request.resolution(),
                Visibility.PUBLIC, false, null);
        messages.save(closing);

        ticket.moveTo(TicketStatus.RESOLVED, now);
        Map<String, Object> effect = sla.stopResolution(ticket);
        eventRecorder.record(ticket, TicketEventType.RESOLVED, from.name(),
                TicketStatus.RESOLVED.name(), Map.of("messageId", closing.getId()));

        // Doc 15 Task 25: queued unconditionally here. The three quality gates
        // (resolution length, not incident-linked, not reopened) are the indexing
        // worker's decision to make, not this method's — deciding here would mean this
        // transaction reaching into Phase 8's incident-link table, which resolve() has
        // no other reason to know about.
        outbox.publish("TICKET", ticket.getId(), EventType.TICKET_RESOLVED,
                Map.of("ticketId", ticket.getId()));

        // The agent's queue shrinks when the work leaves it, not when the ticket is closed
        // days later by a cron job.
        releaseAssignee(ticket, principal.tenantId());
        tickets.saveAndFlush(ticket);

        return new StatusChangeResponse(ticket.getId(), ticket.getStatus(), effect,
                EtagSupport.etagOf(ticket.getVersion()));
    }

    /**
     * Reopens a resolved or closed ticket.
     *
     * <p>Both the requester and an agent may reopen: a customer saying "this is not fixed"
     * is the most reliable signal available that it is not fixed, and making them file a
     * second ticket loses the history that explains the first attempt.
     */
    @Transactional
    public StatusChangeResponse reopen(ResolvePrincipal principal, Long ticketId,
                                       ReopenRequest request) {
        Ticket ticket = access.loadVisibleForUpdate(principal, ticketId);
        TicketStatus from = ticket.getStatus();
        if (from != TicketStatus.RESOLVED && from != TicketStatus.CLOSED) {
            throw new IllegalTransitionException(from, TicketStatus.OPEN);
        }

        AppUser author = access.caller(principal);
        messages.save(new TicketMessage(ticket, author, request.reason(), Visibility.PUBLIC,
                false, null));

        // reopen_count is an input to the Phase 6 priority policy: a ticket reopened twice
        // is not a routine ticket, whatever the model thinks of its text.
        ticket.reopen();
        sla.restartResolution(ticket);
        eventRecorder.record(ticket, TicketEventType.REOPENED, from.name(),
                TicketStatus.OPEN.name(), Map.of("reopenCount", ticket.getReopenCount()));
        tickets.saveAndFlush(ticket);

        return new StatusChangeResponse(ticket.getId(), ticket.getStatus(), Map.of(),
                EtagSupport.etagOf(ticket.getVersion()));
    }

    private void releaseAssignee(Ticket ticket, Long tenantId) {
        if (ticket.getAssignee() != null) {
            agentProfiles.decrementOpenCount(ticket.getAssignee().getId(), tenantId);
        }
    }

    private static void requireNotClosed(Ticket ticket) {
        if (ticket.isClosed()) {
            throw new ApiException(ErrorCode.TICKET_CLOSED,
                    "Ticket " + ticket.getReference() + " is closed.");
        }
    }

    /**
     * A 409 that carries the permitted targets.
     *
     * <p>Its own type rather than a plain {@link ApiException} because the handler has to
     * add {@code allowedTransitions} to the problem body, and threading an extra field
     * through every {@code ApiException} for the sake of one code is worse than one small
     * subclass.
     */
    public static final class IllegalTransitionException extends ApiException {

        private final transient List<String> allowedTransitions;

        IllegalTransitionException(TicketStatus from, TicketStatus to) {
            super(ErrorCode.ILLEGAL_TRANSITION, "Cannot move from " + from + " to " + to + ".");
            this.allowedTransitions = TicketStateMachine.allowedNamesFrom(from);
        }

        public List<String> allowedTransitions() {
            return allowedTransitions;
        }
    }
}
