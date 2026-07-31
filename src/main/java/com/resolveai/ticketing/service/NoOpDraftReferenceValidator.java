package com.resolveai.ticketing.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The stand-in used until the {@code drafting} package contributes the real
 * {@link DraftReferenceValidator} — the same {@code @ConditionalOnMissingBean} pattern
 * {@code NoOpSlaLifecycle} established in Phase 5, and for the identical reason: this
 * bean's mere absence, once the real one exists, is what switches every call site over
 * with no edit at any of them.
 *
 * <p>{@code requireValid} does nothing here, which means an unvalidated
 * {@code fromDraftId} is accepted exactly as it was before this task — a deliberate,
 * temporary gap that closes the moment {@code DraftReferenceValidatorService} is
 * registered.
 */
@Configuration
public class NoOpDraftReferenceValidator {

    @Bean
    @ConditionalOnMissingBean(DraftReferenceValidator.class)
    DraftReferenceValidator draftReferenceValidator() {
        return new DraftReferenceValidator() {

            @Override
            public void requireValid(Long ticketId, Long draftId) {
                // Phase 7C.
            }

            @Override
            public void recordAutomaticAction(Long draftId, Long agentId, String sentBody) {
                // Phase 7C.
            }
        };
    }
}
