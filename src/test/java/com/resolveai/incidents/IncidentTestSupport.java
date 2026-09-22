package com.resolveai.incidents;

import com.resolveai.platform.ai.model.EmbeddingService;
import com.resolveai.ticketing.domain.TicketEntityType;
import java.time.OffsetDateTime;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Fixtures for the incident-correlation tests: tickets with embeddings and entities. */
@Component
public class IncidentTestSupport {

    public static final int DIMENSIONS = 768;

    @Autowired JdbcTemplate jdbc;

    private final AtomicInteger sequence = new AtomicInteger(1);

    /**
     * A ticket whose embedding is a near-one-hot vector on {@code axis}, with tiny noise
     * elsewhere — tickets sharing an axis cluster tightly; tickets on different axes are
     * near-orthogonal. Mirrors {@code TicketClustererTest}'s construction, against a real
     * database instead of an in-memory list.
     */
    public Long seedTicket(Long tenantId, Long requesterId, String subject, int axis,
                           OffsetDateTime createdAt) {
        Long id = jdbc.queryForObject("""
                INSERT INTO ticket (tenant_id, reference, subject, body, status, requester_id,
                                    created_at)
                VALUES (?, ?, ?, 'seeded for a correlation test', 'TRIAGED', ?, ?)
                RETURNING id
                """, Long.class, tenantId, "TKT-IC-" + sequence.getAndIncrement(), subject,
                requesterId, createdAt);

        float[] vector = axisVector(axis, id);
        jdbc.update("UPDATE ticket SET embedding = ?::vector WHERE id = ?",
                EmbeddingService.toVectorLiteral(vector), id);
        return id;
    }

    public void addEntity(Long tenantId, Long ticketId, TicketEntityType type, String value) {
        jdbc.update("""
                INSERT INTO ticket_entity (ticket_id, tenant_id, entity_type, entity_value)
                VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, ticketId, tenantId, type.name(), value);
    }

    public void refreshBaseline() {
        // CONCURRENTLY needs the view already populated once; ordinary REFRESH is fine
        // inside a test where nothing else reads it concurrently.
        jdbc.execute("REFRESH MATERIALIZED VIEW ticket_arrival_baseline");
    }

    private static float[] axisVector(int axis, long seed) {
        Random random = new Random(seed * 31 + axis);
        float[] v = new float[DIMENSIONS];
        for (int i = 0; i < DIMENSIONS; i++) {
            v[i] = (random.nextFloat() - 0.5f) * 0.02f;
        }
        v[Math.floorMod(axis, DIMENSIONS)] = 1.0f;
        return v;
    }
}
