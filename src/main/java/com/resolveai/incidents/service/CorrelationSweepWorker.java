package com.resolveai.incidents.service;

import com.resolveai.incidents.domain.DetectionMethod;
import com.resolveai.incidents.domain.Incident;
import com.resolveai.incidents.domain.IncidentStatus;
import com.resolveai.incidents.domain.IncidentTicket;
import com.resolveai.incidents.repository.CorrelationCandidateRepository;
import com.resolveai.incidents.repository.CorrelationCandidateRepository.CandidateRow;
import com.resolveai.incidents.repository.IncidentRepository;
import com.resolveai.incidents.repository.IncidentTicketRepository;
import com.resolveai.incidents.service.CorrelationGate.GateConfig;
import com.resolveai.incidents.service.CorrelationGate.GateDecision;
import com.resolveai.incidents.service.CorrelationGate.GateInput;
import com.resolveai.incidents.service.TicketClusterer.CandidateTicket;
import com.resolveai.incidents.service.TicketClusterer.Cluster;
import com.resolveai.platform.ai.model.EmbeddingService;
import com.resolveai.platform.sequence.ReferenceGenerator;
import com.resolveai.platform.sequence.ReferenceGenerator.EntityType;
import com.resolveai.platform.tenant.TenantContext;
import com.resolveai.platform.time.DatabaseClock;
import com.resolveai.ticketing.domain.Ticket;
import com.resolveai.ticketing.domain.TicketEntity;
import com.resolveai.ticketing.repository.TicketEntityRepository;
import com.resolveai.ticketing.repository.TicketRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wakes every minute, clusters each tenant's recent tickets, and proposes an incident for
 * every cluster the gate accepts.
 *
 * <h2>Fail closed on the lock — the opposite of the rate limiter's posture</h2>
 *
 * <p>Two sweeps running concurrently for the same tenant would propose duplicate incidents
 * for the same storm: visible, confusing, and it would undermine trust in the feature
 * immediately. Skipping a 60-second sweep for that tenant costs at most one sweep of
 * latency. {@code IdempotencyStore} makes the identical trade for the identical reason —
 * see its class comment — and this reuses its {@code setIfAbsent} pattern.
 *
 * <p><b>Release is a plain {@code DEL}, not a compare-and-delete.</b> There is no
 * ownership-token release anywhere in this codebase to copy — see the doc 12 survey — and
 * the exposure it would close is narrow: only a sweep that ran past its own 55-second TTL
 * could have its lock deleted out from under it by a later sweep, which is already the
 * degenerate case the TTL exists to bound.
 */
@Component
public class CorrelationSweepWorker {

    private static final Logger log = LoggerFactory.getLogger(CorrelationSweepWorker.class);
    private static final String LOCK_PREFIX = "lock:correlate:";

    private final StringRedisTemplate redis;
    private final CorrelationCandidateRepository candidates;
    private final TicketEntityRepository ticketEntities;
    private final TicketRepository tickets;
    private final TicketClusterer clusterer;
    private final CorrelationGate gate;
    private final GateConfig gateConfig;
    private final BaselineService baselines;
    private final IncidentTitleGenerator titleGenerator;
    private final IncidentRepository incidents;
    private final IncidentTicketRepository incidentTickets;
    private final ReferenceGenerator references;
    private final TransactionTemplate txTemplate;
    private final DatabaseClock clock;
    private final int windowMinutes;
    private final Duration lockTtl;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public CorrelationSweepWorker(StringRedisTemplate redis,
                                  CorrelationCandidateRepository candidates,
                                  TicketEntityRepository ticketEntities, TicketRepository tickets,
                                  TicketClusterer clusterer, CorrelationGate gate,
                                  GateConfig gateConfig, BaselineService baselines,
                                  IncidentTitleGenerator titleGenerator,
                                  IncidentRepository incidents,
                                  IncidentTicketRepository incidentTickets,
                                  ReferenceGenerator references, TransactionTemplate txTemplate,
                                  DatabaseClock clock,
                                  @Value("${resolveai.correlation.window-minutes:30}")
                                  int windowMinutes,
                                  @Value("${resolveai.correlation.sweep-lock-ttl-seconds:55}")
                                  long lockTtlSeconds) {
        this.redis = redis;
        this.candidates = candidates;
        this.ticketEntities = ticketEntities;
        this.tickets = tickets;
        this.clusterer = clusterer;
        this.gate = gate;
        this.gateConfig = gateConfig;
        this.baselines = baselines;
        this.titleGenerator = titleGenerator;
        this.incidents = incidents;
        this.incidentTickets = incidentTickets;
        this.references = references;
        this.txTemplate = txTemplate;
        this.clock = clock;
        this.windowMinutes = windowMinutes;
        this.lockTtl = Duration.ofSeconds(lockTtlSeconds);
    }

    @Scheduled(fixedDelayString = "${resolveai.correlation.sweep-interval-ms:60000}")
    public void sweep() {
        try {
            int proposed = sweepOnce();
            if (proposed > 0) {
                log.info("Correlation sweep proposed {} incident(s)", proposed);
            }
        } catch (RuntimeException e) {
            log.error("Correlation sweep failed; the next run will retry", e);
        }
    }

    /** One pass over every tenant with recent activity. Public so tests can drive it directly. */
    public int sweepOnce() {
        OffsetDateTime windowStart = clock.now().minusMinutes(windowMinutes);
        List<Long> tenantIds = candidates.activeTenantIds(windowStart);

        int proposed = 0;
        for (Long tenantId : tenantIds) {
            proposed += sweepTenantWithLock(tenantId, windowStart);
        }
        return proposed;
    }

    private int sweepTenantWithLock(Long tenantId, OffsetDateTime windowStart) {
        String lockKey = LOCK_PREFIX + tenantId;
        String token = UUID.randomUUID().toString();
        Boolean acquired;
        try {
            acquired = redis.opsForValue().setIfAbsent(lockKey, token, lockTtl);
        } catch (RuntimeException e) {
            // Fail closed: an unreachable Redis means this tenant is skipped this minute
            // rather than risking two sweeps running unlocked.
            log.warn("Could not acquire correlation lock for tenant {}; skipping this sweep",
                    tenantId, e);
            return 0;
        }
        if (!Boolean.TRUE.equals(acquired)) {
            return 0;
        }
        try {
            return TenantContext.callAs(tenantId, () -> sweepTenant(tenantId, windowStart));
        } finally {
            try {
                redis.delete(lockKey);
            } catch (RuntimeException e) {
                log.warn("Could not release correlation lock for tenant {}; it will expire "
                         + "on its own in {}", tenantId, lockTtl, e);
            }
        }
    }

    private int sweepTenant(Long tenantId, OffsetDateTime windowStart) {
        List<CandidateRow> rows = candidates.findCandidates(tenantId, windowStart);
        int missingEmbeddings = candidates.countMissingEmbeddings(tenantId, windowStart);
        if (missingEmbeddings > 0) {
            log.debug("Tenant {}: {} recent ticket(s) have no embedding yet and are "
                     + "excluded from this sweep", tenantId, missingEmbeddings);
        }
        if (rows.size() < 2) {
            return 0;
        }

        Map<Long, float[]> embeddingByTicket = new HashMap<>();
        List<Long> ticketIds = new ArrayList<>();
        for (CandidateRow row : rows) {
            embeddingByTicket.put(row.ticketId(),
                    EmbeddingService.fromVectorLiteral(row.embeddingLiteral()));
            ticketIds.add(row.ticketId());
        }

        Map<Long, Set<String>> entitiesByTicket = loadEntities(ticketIds);

        List<CandidateTicket> candidateTickets = rows.stream()
                .map(r -> new CandidateTicket(r.ticketId(), embeddingByTicket.get(r.ticketId()),
                        entitiesByTicket.getOrDefault(r.ticketId(), Set.of()),
                        r.createdAt().toInstant()))
                .toList();

        List<Cluster> clusters = clusterer.cluster(candidateTickets);
        int proposed = 0;
        for (Cluster cluster : clusters) {
            if (evaluateAndPropose(tenantId, cluster, embeddingByTicket, entitiesByTicket)) {
                proposed++;
            }
        }
        return proposed;
    }

    private boolean evaluateAndPropose(Long tenantId, Cluster cluster,
                                       Map<Long, float[]> embeddingByTicket,
                                       Map<Long, Set<String>> entitiesByTicket) {
        BaselineService.Baseline baseline = baselines.baselineFor(tenantId, cluster.windowStart());
        List<Long> existingLiveTicketIds = liveTicketIdsForTenant();

        GateDecision decision = gate.evaluate(new GateInput(cluster,
                baseline.count().doubleValue(), baseline.sampleWeeks(),
                baseline.source().name(), gateConfig, existingLiveTicketIds));

        log.debug("Tenant {} cluster of {}: {}", tenantId, cluster.size(), decision.reason());
        if (!decision.propose()) {
            return false;
        }

        txTemplate.executeWithoutResult(status ->
                propose(tenantId, cluster, decision, baseline, embeddingByTicket, entitiesByTicket));
        return true;
    }

    private List<Long> liveTicketIdsForTenant() {
        List<Incident> live = incidents.findByStatusIn(EnumSet.of(
                IncidentStatus.PROPOSED, IncidentStatus.CONFIRMED, IncidentStatus.MITIGATED));
        List<Long> ticketIds = new ArrayList<>();
        for (Incident incident : live) {
            incidentTickets.findLiveByIncidentId(incident.getId())
                    .forEach(link -> ticketIds.add(link.getTicketId()));
        }
        return ticketIds;
    }

    private void propose(Long tenantId, Cluster cluster, GateDecision decision,
                         BaselineService.Baseline baseline, Map<Long, float[]> embeddingByTicket,
                         Map<Long, Set<String>> entitiesByTicket) {
        List<Ticket> members = tickets.findAllById(cluster.ticketIds());
        List<String> representativeSubjects = members.stream()
                .map(Ticket::getSubject).limit(5).toList();
        Set<String> sharedEntityLabels = mostCommonEntities(cluster.ticketIds(), entitiesByTicket);

        IncidentTitleGenerator.TitleResult title = titleGenerator.generate(
                tenantId, representativeSubjects, sharedEntityLabels, cluster.size());

        String reference = references.next(tenantId, EntityType.INCIDENT);
        OffsetDateTime firstTicketAt = OffsetDateTime.ofInstant(cluster.windowStart(), ZoneOffset.UTC);

        Incident incident = new Incident(reference, title.title(), title.summary(),
                title.generatedByModel(), title.promptVersionId(), DetectionMethod.CLUSTER,
                cluster.size(), BigDecimal.valueOf(decision.arrivalRateMultiple()), firstTicketAt);
        incidents.save(incident);

        Long seedTicketId = cluster.ticketIds().get(0);
        float[] seedEmbedding = embeddingByTicket.get(seedTicketId);
        for (Long ticketId : cluster.ticketIds()) {
            BigDecimal confidence = linkConfidence(seedEmbedding, embeddingByTicket.get(ticketId));
            incidentTickets.save(new IncidentTicket(incident.getId(), ticketId, confidence, null));
        }

        log.info("Proposed incident {} ({} tickets, {}x baseline [{}], {})", reference,
                cluster.size(), decision.arrivalRateMultiple(), baseline.source(), decision.reason());
    }

    private static BigDecimal linkConfidence(float[] seed, float[] member) {
        if (seed == null || member == null || seed.length != member.length) {
            return null;
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < seed.length; i++) {
            dot += (double) seed[i] * member[i];
            normA += (double) seed[i] * seed[i];
            normB += (double) member[i] * member[i];
        }
        if (normA == 0 || normB == 0) {
            return BigDecimal.ZERO;
        }
        double cosine = Math.max(0.0, Math.min(1.0, dot / (Math.sqrt(normA) * Math.sqrt(normB))));
        return BigDecimal.valueOf(cosine).setScale(3, RoundingMode.HALF_UP);
    }

    private Map<Long, Set<String>> loadEntities(List<Long> ticketIds) {
        Map<Long, Set<String>> byTicket = new LinkedHashMap<>();
        for (TicketEntity entity : ticketEntities.findByTicketIdIn(ticketIds)) {
            byTicket.computeIfAbsent(entity.getTicketId(), k -> new java.util.LinkedHashSet<>())
                    .add(TicketClusterer.entityKey(entity.getEntityType().name(), entity.getEntityValue()));
        }
        return byTicket;
    }

    /** The two or three entity keys that appear on the most cluster members — the title's evidence. */
    private static Set<String> mostCommonEntities(List<Long> ticketIds,
                                                   Map<Long, Set<String>> entitiesByTicket) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Long ticketId : ticketIds) {
            for (String key : entitiesByTicket.getOrDefault(ticketId, Set.of())) {
                counts.merge(key, 1, Integer::sum);
            }
        }
        return counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(3)
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }
}
