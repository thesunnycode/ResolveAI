package com.resolveai.platform.sequence;

import com.resolveai.platform.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hands out the human-facing references customers quote back to you: {@code TKT-10428}.
 *
 * <p><b>No migration was needed for this.</b> Doc 10 Task 2 asks for a {@code V8}
 * creating {@code tenant_sequence}, on the grounds that doc 04 defines no way to generate
 * {@code ticket.reference}. That gap was already closed during Phase 2 Task 2 - the table
 * is in {@code V1__extensions_and_tenancy.sql} with the same shape and the same reasoning
 * in its comment. Writing V8 anyway produced {@code relation "tenant_sequence" already
 * exists} on the next test run, which is the cheapest possible way to be told that the
 * plan and the schema had already diverged.
 *
 * <h2>Why a counter table and not a sequence or the primary key</h2>
 *
 * <p>A Postgres sequence is global to the database. Prefixing the {@code BIGSERIAL} id gives
 * {@code TKT-88213} on a tenant's very first ticket, which discloses roughly how many
 * tickets every other tenant has ever raised. A per-tenant counter discloses only that
 * tenant's own volume, to that tenant.
 *
 * <h2>Why {@code MANDATORY} rather than opening its own transaction</h2>
 *
 * <p>The row lock has to be held until the ticket is actually inserted. If this method
 * committed on its own, a create that then failed validation would burn a number and leave
 * a gap — and, worse, two concurrent creates could both take their number, both roll back,
 * and the next ticket would be {@code TKT-1002} with 1000 and 1001 never existing. Running
 * inside the caller's transaction makes the number and the row atomic: <b>a rolled-back
 * create consumes nothing.</b>
 *
 * <p>{@code MANDATORY} also means a call from outside a transaction fails at the first test
 * run rather than quietly auto-committing in production.
 *
 * <h2>Why raw JDBC</h2>
 *
 * <p>{@code INSERT ... ON CONFLICT DO UPDATE ... RETURNING} is one round trip and is
 * atomic without a read-modify-write. Expressed through JPA it would be an entity, a
 * pessimistic lock, a null check and three statements — all to reproduce what one statement
 * already does correctly.
 */
@Component
public class ReferenceGenerator {

    /** The kinds of thing that get a per-tenant reference. Matches {@code ck_tenseq_entity}. */
    public enum EntityType {
        TICKET("TKT"),
        INCIDENT("INC");

        private final String prefix;

        EntityType(String prefix) {
            this.prefix = prefix;
        }

        public String prefix() {
            return prefix;
        }
    }

    /**
     * Upsert-and-increment in a single statement.
     *
     * <p>The {@code ON CONFLICT} arm is what makes the first ticket for a brand-new tenant
     * work without a separate "create the counter" step — and, because the whole thing is one
     * statement, without a race between two first tickets. {@code RETURNING} gives back the
     * value that was just consumed, rather than the next one.
     */
    private static final String NEXT_VALUE = """
            INSERT INTO tenant_sequence (tenant_id, entity_type, next_value)
            VALUES (?, ?, 1001)
            ON CONFLICT (tenant_id, entity_type)
                DO UPDATE SET next_value = tenant_sequence.next_value + 1
            RETURNING next_value - 1
            """;

    private final JdbcTemplate jdbc;

    public ReferenceGenerator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The next reference for the current tenant, e.g. {@code TKT-1000}.
     *
     * <p>Reads the tenant from {@link TenantContext} rather than taking it as a parameter,
     * for the same reason every other tenant-scoped read does: a caller that can pass a
     * tenant id is a caller that can pass the wrong one.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String next(EntityType entityType) {
        return next(TenantContext.getRequired(), entityType);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String next(Long tenantId, EntityType entityType) {
        Long value = jdbc.queryForObject(NEXT_VALUE, Long.class, tenantId, entityType.name());
        return entityType.prefix() + "-" + value;
    }
}
