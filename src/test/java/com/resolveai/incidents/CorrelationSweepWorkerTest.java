package com.resolveai.incidents;

import static org.assertj.core.api.Assertions.assertThat;

import com.resolveai.AuthTestSupport;
import com.resolveai.IntegrationTestBase;
import com.resolveai.incidents.domain.Incident;
import com.resolveai.incidents.domain.IncidentStatus;
import com.resolveai.incidents.repository.IncidentRepository;
import com.resolveai.incidents.repository.IncidentTicketRepository;
import com.resolveai.incidents.service.CorrelationSweepWorker;
import com.resolveai.platform.tenant.TenantScope;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Doc 12 Task 9's expected output, against a real database: a seeded storm produces one
 * proposed incident; an uncorrelated burst produces none.
 */
class CorrelationSweepWorkerTest extends IntegrationTestBase {

    @Autowired AuthTestSupport auth;
    @Autowired IncidentTestSupport fixtures;
    @Autowired CorrelationSweepWorker sweeper;
    @Autowired IncidentRepository incidents;
    @Autowired IncidentTicketRepository incidentTickets;
    @Autowired TenantScope tenantScope;
    @Autowired JdbcTemplate jdbc;

    private AuthTestSupport.SeededTenant tenant;

    @BeforeEach
    void setUp() {
        auth.wipe();
        tenant = auth.seedTenant("storm");
    }

    @Test
    @DisplayName("a tight cluster of 8 tickets in a 5-minute window proposes one incident")
    void seededStormProposesOneIncident() {
        OffsetDateTime now = jdbc.queryForObject("SELECT NOW()", OffsetDateTime.class);
        for (int i = 0; i < 8; i++) {
            fixtures.seedTicket(tenant.tenantId(), tenant.customerId(),
                    "Payment failing in different words #" + i, 0,
                    now.minusMinutes(5).plusSeconds(i * 30L));
        }
        fixtures.refreshBaseline();

        int proposed = sweeper.sweepOnce();

        assertThat(proposed).isEqualTo(1);
        List<Incident> live = tenantScope.inTenant(tenant.tenantId(),
                () -> incidents.findByStatusIn(EnumSet.of(IncidentStatus.PROPOSED)));
        assertThat(live).hasSize(1);
        Incident incident = live.get(0);
        assertThat(incident.getClusterSizeAtDetection()).isEqualTo(8);
        assertThat(incident.getReference()).startsWith("INC-");
        // No AI policy configured for this tenant -> the template fallback, proving the
        // incident exists whether or not a model was reachable.
        assertThat(incident.getGeneratedByModel()).isNull();
        assertThat(incident.getTitle()).contains("8");

        assertThat(incidentTickets.findLiveByIncidentId(incident.getId())).hasSize(8);
    }

    @Test
    @DisplayName("30 tickets about 30 different things produce zero incidents")
    void uncorrelatedBurstProposesNothing() {
        OffsetDateTime now = jdbc.queryForObject("SELECT NOW()", OffsetDateTime.class);
        for (int i = 0; i < 30; i++) {
            // Each on its own axis: no two tickets share a dominant embedding dimension,
            // so no pair clusters and nothing is proposed.
            fixtures.seedTicket(tenant.tenantId(), tenant.customerId(),
                    "Unrelated question number " + i, i * 17, now.minusMinutes(2));
        }
        fixtures.refreshBaseline();

        int proposed = sweeper.sweepOnce();

        assertThat(proposed).isZero();
        List<Incident> live = tenantScope.inTenant(tenant.tenantId(),
                () -> incidents.findByStatusIn(EnumSet.of(IncidentStatus.PROPOSED)));
        assertThat(live).isEmpty();
    }

    @Test
    @DisplayName("a storm arriving while an incident is already confirmed proposes no duplicate")
    void confirmedIncidentIsNotDuplicated() {
        OffsetDateTime now = jdbc.queryForObject("SELECT NOW()", OffsetDateTime.class);
        List<Long> firstBatch = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            firstBatch.add(fixtures.seedTicket(tenant.tenantId(), tenant.customerId(),
                    "Payment failing #" + i, 1, now.minusMinutes(10).plusSeconds(i * 30L)));
        }
        fixtures.refreshBaseline();
        assertThat(sweeper.sweepOnce()).isEqualTo(1);

        Long incidentId = tenantScope.inTenant(tenant.tenantId(), () ->
                incidents.findByStatusIn(EnumSet.of(IncidentStatus.PROPOSED)).get(0).getId());
        jdbc.update("UPDATE incident SET status = 'CONFIRMED' WHERE id = ?", incidentId);

        // Five more tickets land on the same storm, overlapping heavily with the confirmed
        // incident's own tickets - the >50% overlap condition must suppress a duplicate.
        for (int i = 0; i < 5; i++) {
            fixtures.seedTicket(tenant.tenantId(), tenant.customerId(),
                    "Payment still failing #" + i, 1, now.minusSeconds(90).plusSeconds(i * 10L));
        }

        int proposedAgain = sweeper.sweepOnce();

        assertThat(proposedAgain).isZero();
    }
}
