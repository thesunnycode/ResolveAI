package com.resolveai.incidents.service;

import com.resolveai.incidents.domain.Incident;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns a team lead's confirm/reject decision into a labelled example, {@code suite =
 * 'INCIDENT', source = 'INCIDENT_CONFIRM'}.
 *
 * <p><b>Rejections are the more valuable half of this set.</b> They are exactly the false
 * positives doc 12 Task 11's tuning is trying to eliminate, discovered in real use rather
 * than in a synthetic test — see {@code IncidentLifecycleService.reject}'s comment.
 */
@Component
public class IncidentEvalCaseWriter {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public IncidentEvalCaseWriter(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Incident incident, List<Long> linkedTicketIds, boolean confirmed,
                       String rejectedReason) {
        String fixture = objectMapper.writeValueAsString(Map.of(
                "clusterSizeAtDetection", incident.getClusterSizeAtDetection(),
                "arrivalRateMultiple", incident.getArrivalRateMultiple() == null
                        ? null : incident.getArrivalRateMultiple().doubleValue(),
                "ticketIds", linkedTicketIds));
        String expected = objectMapper.writeValueAsString(confirmed
                ? Map.of("label", "CONFIRMED")
                : Map.of("label", "REJECTED", "reason", rejectedReason));

        // ON CONFLICT DO UPDATE: a case can only be confirmed or rejected once per
        // incident in practice, but idempotent by construction costs nothing here.
        jdbc.update("""
                INSERT INTO eval_case (suite, name, input_fixture, expected, source)
                VALUES ('INCIDENT', ?, CAST(? AS jsonb), CAST(? AS jsonb), 'INCIDENT_CONFIRM')
                ON CONFLICT (suite, name) DO UPDATE
                   SET input_fixture = EXCLUDED.input_fixture, expected = EXCLUDED.expected
                """, incident.getReference(), fixture, expected);
    }
}
