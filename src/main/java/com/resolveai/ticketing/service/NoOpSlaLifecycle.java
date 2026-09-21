package com.resolveai.ticketing.service;

import com.resolveai.ticketing.domain.Ticket;
import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The Phase 5A stand-in for the SLA engine: every method does nothing.
 *
 * <p><b>{@code @ConditionalOnMissingBean} is what makes Task 27 a deletion rather than an
 * edit.</b> When the real {@code SlaLifecycleService} appears in the {@code sla} package,
 * this bean stops being registered and every call site starts doing real work — with no
 * change to any of them, and no chance of one being missed.
 *
 * <p>The bean method is named {@code slaLifecycle} rather than after the class: a
 * {@code @Configuration} class is itself a bean under its own decapitalised name, and a
 * {@code @Bean} method of the same name inside it is a duplicate definition that refuses to
 * start with "a bean with that name has already been defined" - which reads like a
 * component-scan problem rather than a naming one.
 *
 * <p>It returns empty maps rather than {@code null} so that a response's {@code slaEffect}
 * field is an empty object instead of a hole, which keeps the Phase 5A response shape the
 * same one Phase 5B produces.
 */
@Configuration
public class NoOpSlaLifecycle {

    @Bean
    @ConditionalOnMissingBean(SlaLifecycle.class)
    SlaLifecycle slaLifecycle() {
        return new SlaLifecycle() {

            @Override
            public void start(Ticket ticket) {
                // Phase 5B.
            }

            @Override
            public SlaOutcome markFirstResponseMet(Ticket ticket, OffsetDateTime at) {
                return null;
            }

            @Override
            public Map<String, Object> pauseResolution(Ticket ticket, String reason) {
                return Map.of();
            }

            @Override
            public Map<String, Object> resumeResolution(Ticket ticket) {
                return Map.of();
            }

            @Override
            public Map<String, Object> stopResolution(Ticket ticket) {
                return Map.of();
            }

            @Override
            public void restartResolution(Ticket ticket) {
                // Phase 5B.
            }
        };
    }
}
