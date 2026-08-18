package com.resolveai.triage;

import com.resolveai.triage.policy.PriorityPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the priority policy as a bean <b>without putting Spring inside it</b>.
 *
 * <p>{@code @Service} on {@link PriorityPolicy} would be one character shorter and would
 * cost the thing that makes the class worth defending: it is a pure function, its test
 * needs no context, and nothing in it can quietly acquire a repository later. A
 * {@code @Service} annotation is an open invitation to inject one — and the first
 * injected dependency is the end of "you can run this in a unit test in a millisecond".
 *
 * <p>So the wiring lives here, one method away, where it is the container's business and
 * not the policy's.
 */
@Configuration
public class TriageConfig {

    @Bean
    PriorityPolicy priorityPolicy() {
        return new PriorityPolicy();
    }
}
