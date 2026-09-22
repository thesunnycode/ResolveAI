package com.resolveai.incidents.service;

import com.resolveai.incidents.service.CorrelationGate.GateConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires {@link TicketClusterer} and {@link CorrelationGate.GateConfig} from
 * {@code resolveai.correlation.*} — kept out of the two classes themselves so they stay
 * plain, dependency-free objects that a unit test constructs directly with whatever
 * {@code tau}/{@code entityBoost} it is sweeping (doc 12 Task 11).
 */
@Configuration
public class CorrelationConfig {

    @Bean
    public TicketClusterer ticketClusterer(
            @Value("${resolveai.correlation.tau:0.82}") double tau,
            @Value("${resolveai.correlation.entity-boost:0.15}") double entityBoost) {
        return new TicketClusterer(tau, entityBoost);
    }

    @Bean
    public GateConfig gateConfig(
            @Value("${resolveai.correlation.min-cluster-size:5}") int minClusterSize,
            @Value("${resolveai.correlation.rate-multiplier:3.0}") double minRateMultiple,
            @Value("${resolveai.correlation.window-minutes:30}") int maxWindowMinutes,
            @Value("${resolveai.correlation.max-overlap-fraction:0.50}") double maxOverlap) {
        return new GateConfig(minClusterSize, minRateMultiple, maxWindowMinutes, maxOverlap);
    }

    @Bean
    public CorrelationGate correlationGate() {
        return new CorrelationGate();
    }
}
