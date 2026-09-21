package com.resolveai.ticketing.service;

import com.resolveai.sla.web.dto.SlaResponse;
import com.resolveai.ticketing.web.dto.SlaSummary;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The read side of the SLA port: what ticketing needs to <i>render</i> a clock, as opposed
 * to {@link SlaLifecycle}, which is what it needs to <i>drive</i> one.
 *
 * <p>Split from {@code SlaLifecycle} on purpose. The mapper is used on read-only paths and
 * has no business being able to pause anything; giving it the lifecycle interface would put
 * six mutating methods within reach of a method whose job is to build JSON.
 */
public interface SlaSummaryProvider {

    /** The compact form on a queue row. {@code null} before the clocks have started. */
    SlaSummary summaryFor(Long ticketId);

    /** The full form with segment histories, for the detail view. */
    SlaResponse detailFor(Long ticketId);

    /**
     * Phase 5A's stand-in. Replaced without touching a call site when the {@code sla}
     * package contributes a real bean — see {@link NoOpSlaLifecycle} for the same pattern
     * and the reasoning behind it.
     */
    @Configuration
    class NoOp {

        @Bean
        @ConditionalOnMissingBean(SlaSummaryProvider.class)
        SlaSummaryProvider noOpSlaSummaryProvider() {
            return new SlaSummaryProvider() {

                @Override
                public SlaSummary summaryFor(Long ticketId) {
                    return null;
                }

                @Override
                public SlaResponse detailFor(Long ticketId) {
                    return null;
                }
            };
        }
    }
}
