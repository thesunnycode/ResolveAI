package com.resolveai.triage.service;

import com.resolveai.iam.security.ResolvePrincipal;
import com.resolveai.ticketing.domain.Ticket;
import com.resolveai.ticketing.service.TicketAccess;
import com.resolveai.triage.TriageRepository;
import com.resolveai.triage.policy.RuleTrace;
import com.resolveai.triage.web.dto.PriorityRationaleResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads back a stored priority decision and renders it.
 *
 * <h2>Read, never recompute</h2>
 *
 * <p>The rationale is reconstructed entirely from the {@code priority_decision} row: the
 * snapshotted inputs, the rule trace as it was produced, and the policy version in force
 * at the time. Nothing here calls {@code PriorityPolicy} again.
 *
 * <p>That is deliberate and it is the whole value of persisting the trace. Re-evaluating
 * on read would explain a decision made in March with June's rules, June's plan tier and
 * June's reopen count — an argument that was never made, presented as the one that was.
 * It would also be self-confirming: the rationale would always reconstruct the current
 * priority exactly, including on the tickets where it should not, which is precisely
 * where the endpoint has to be trustworthy.
 *
 * <h2>No decision is a legitimate answer</h2>
 *
 * <p>A ticket whose triage failed, or which is still queued, or whose priority came from
 * the SLA fallback, has a priority and no decision behind it. The honest response says
 * so rather than 404-ing: the priority is real and an agent is looking at it, and "no
 * policy decided this" is exactly what they need to know before overriding it.
 */
@Service
public class PriorityRationaleService {

    private final TicketAccess access;
    private final TriageRepository triage;
    private final ObjectMapper objectMapper;

    public PriorityRationaleService(TicketAccess access, TriageRepository triage,
                                    ObjectMapper objectMapper) {
        this.access = access;
        this.triage = triage;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public PriorityRationaleResponse forTicket(ResolvePrincipal principal, Long ticketId) {
        Ticket ticket = access.loadVisible(principal, ticketId);
        Optional<TriageRepository.DecisionRow> decision =
                triage.latestDecision(principal.tenantId(), ticketId);

        if (decision.isEmpty()) {
            return new PriorityRationaleResponse(ticketId, ticket.getPriority().name(),
                    null, null, Map.of(), List.of(),
                    "No policy decision has been recorded for this ticket. Its priority "
                    + "was set by a human, by the SLA fallback, or not at all.",
                    !ticket.isClosed());
        }

        TriageRepository.DecisionRow row = decision.get();
        Rationale rationale = readRationale(row.rationale());

        return new PriorityRationaleResponse(
                ticketId,
                row.priority().name(),
                row.policyVersion(),
                row.decidedAt(),
                readInputs(row.inputSignals()),
                rationale.rules(),
                // The stored sentence, not a regenerated one. A sentence generated now
                // would describe today's rules; this one is what the policy said.
                rationale.humanReadable(),
                !ticket.isClosed());
    }

    /**
     * The rules as stored.
     *
     * <p>Tolerant of an older shape on purpose. {@code rationale} is JSONB written by
     * whatever version of the policy was deployed at the time, and a strict read would
     * fail on exactly the historical rows somebody is looking at <i>because</i> they are
     * historical — turning "explain this old decision" into a 500.
     */
    private Rationale readRationale(String json) {
        if (json == null || json.isBlank()) {
            return new Rationale(List.of(), "");
        }
        try {
            return objectMapper.readValue(json, Rationale.class);
        } catch (RuntimeException e) {
            // A bare array: the shape written before humanReadable was stored alongside.
            List<RuleTrace> rules = objectMapper.readValue(json,
                    new TypeReference<List<RuleTrace>>() { });
            return new Rationale(rules, "");
        }
    }

    private Map<String, Object> readInputs(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
    }

    /** The {@code rationale} column's shape: the trace plus the sentence it produced. */
    private record Rationale(List<RuleTrace> rules, String humanReadable) {

        Rationale {
            rules = rules == null ? List.of() : List.copyOf(rules);
            humanReadable = humanReadable == null ? "" : humanReadable;
        }
    }
}
