package com.resolveai.ticketing;

import static org.assertj.core.api.Assertions.assertThat;

import com.resolveai.AuthTestSupport;
import com.resolveai.IntegrationTestBase;
import com.resolveai.platform.sequence.ReferenceGenerator;
import com.resolveai.platform.tenant.TenantScope;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The per-tenant reference counter.
 *
 * <p>Two properties matter and only one of them is obvious. The obvious one is that
 * references increment. The one worth a test is that <b>ten concurrent callers get ten
 * distinct values with no gaps</b> — the counter is a read-modify-write and would silently
 * hand out duplicates without the row lock the upsert takes, which
 * {@code uq_ticket_reference} would then turn into a constraint violation on ticket
 * creation, at the worst possible moment and with an error that names the wrong thing.
 */
class ReferenceGeneratorTest extends IntegrationTestBase {

    @Autowired AuthTestSupport auth;
    @Autowired ReferenceGenerator references;
    @Autowired TenantScope tenantScope;

    private AuthTestSupport.SeededTenant alpha;
    private AuthTestSupport.SeededTenant beta;

    @BeforeEach
    void seed() {
        auth.wipe();
        alpha = auth.seedTenant("refa");
        beta = auth.seedTenant("refb");
    }

    @Test
    @DisplayName("references start at 1000 and increment")
    void referencesIncrement() {
        List<String> issued = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            issued.add(tenantScope.inTenant(alpha.tenantId(),
                    () -> references.next(ReferenceGenerator.EntityType.TICKET)));
        }

        assertThat(issued).containsExactly(
                "TKT-1000", "TKT-1001", "TKT-1002", "TKT-1003", "TKT-1004");
    }

    @Test
    @DisplayName("each tenant has its own counter, so nobody's volume is visible to anyone else")
    void countersAreIndependent() {
        tenantScope.inTenant(alpha.tenantId(),
                () -> references.next(ReferenceGenerator.EntityType.TICKET));
        tenantScope.inTenant(alpha.tenantId(),
                () -> references.next(ReferenceGenerator.EntityType.TICKET));

        String firstForBeta = tenantScope.inTenant(beta.tenantId(),
                () -> references.next(ReferenceGenerator.EntityType.TICKET));

        // The whole point of not using the primary key: beta's first ticket must not
        // announce that alpha already has two.
        assertThat(firstForBeta).isEqualTo("TKT-1000");
    }

    @Test
    @DisplayName("tickets and incidents count separately within a tenant")
    void entityTypesAreSeparate() {
        tenantScope.inTenant(alpha.tenantId(),
                () -> references.next(ReferenceGenerator.EntityType.TICKET));

        assertThat(tenantScope.inTenant(alpha.tenantId(),
                () -> references.next(ReferenceGenerator.EntityType.INCIDENT)))
                .isEqualTo("INC-1000");
    }

    @Test
    @DisplayName("ten concurrent callers get ten distinct references and no gaps")
    void concurrentCallersDoNotCollide() throws Exception {
        int threads = 10;
        CountDownLatch release = new CountDownLatch(1);
        // A latch, never a sleep. A sleep-based concurrency test is flaky, gets @Disabled
        // within a week, and then protects nothing.
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    release.await(5, TimeUnit.SECONDS);
                    return tenantScope.inTenant(alpha.tenantId(),
                            () -> references.next(ReferenceGenerator.EntityType.TICKET));
                }));
            }
            release.countDown();

            List<String> issued = new ArrayList<>();
            for (Future<String> f : futures) {
                issued.add(f.get(30, TimeUnit.SECONDS));
            }

            assertThat(issued).doesNotHaveDuplicates().hasSize(threads);
            assertThat(issued).containsExactlyInAnyOrder(
                    "TKT-1000", "TKT-1001", "TKT-1002", "TKT-1003", "TKT-1004",
                    "TKT-1005", "TKT-1006", "TKT-1007", "TKT-1008", "TKT-1009");
        }
    }
}
