package com.resolveai.eval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Puts the labelled cases in the database at startup, locally.
 *
 * <p>Guarded the same three ways as {@code IamSeeder} — profile, property, and the
 * upsert being idempotent — because a seeder that runs somewhere it should not is the
 * kind of thing that is only noticed once it has overwritten something.
 *
 * <p>Not a migration, for the reason in {@link EvalCaseRepository}: an eval suite grows
 * and its labels get corrected, and a migration is immutable once applied. Not part of
 * the test profile either; the eval test seeds explicitly, so that a test which cares
 * about the suite's contents controls them rather than inheriting whatever a startup
 * hook happened to do.
 */
@Component
@Profile("local")
@ConditionalOnProperty(name = "resolveai.seed.enabled", havingValue = "true",
        matchIfMissing = true)
public class EvalCaseSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalCaseSeeder.class);

    private final EvalCaseRepository cases;

    public EvalCaseSeeder(EvalCaseRepository cases) {
        this.cases = cases;
    }

    @Override
    public void run(String... args) {
        int seeded = cases.seed();
        log.info("Eval suite ready: {} CLASSIFICATION cases", seeded);
    }
}
