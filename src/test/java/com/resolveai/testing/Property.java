package com.resolveai.testing;

import static org.assertj.core.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A small property-testing harness: generate many random cases, shrink the first failure,
 * and report a seed that reproduces it exactly.
 *
 * <h2>Why this exists rather than a dependency</h2>
 *
 * <p>Doc 10 defers the property-testing library choice to Phase 5 Task 20, in front of the
 * first real property test, between three options.
 *
 * <ul>
 *   <li><b>jqwik</b> — the best of the three technically, and it was verified to work on
 *       Boot 4's JUnit Platform 6 before this was written. Its test output carries a
 *       message from the project asking AI agents not to use the library. That is the
 *       maintainer's call to make about their own work, it costs nothing to respect, and
 *       this code was written by one. So: not used.
 *   <li><b>QuickTheories 0.26</b> — unmaintained since 2018. Taking an eight-year-dormant
 *       dependency to avoid writing a hundred lines is the wrong trade in a project whose
 *       whole argument is that dependencies are chosen deliberately.
 *   <li><b>Seeded generators over plain JUnit 5</b> — this. No dependency, and the two
 *       properties that actually matter for finding bugs are reproducibility and
 *       shrinking, both of which fit in one file.
 * </ul>
 *
 * <p>What is given up is real, and worth stating: no automatic generators for arbitrary
 * types, no statistics, no edge-case biasing beyond what is written here by hand. If the
 * suite grows past business-hours arithmetic, swapping in a library is a one-line change to
 * the pom and a rewrite of this file's three callers.
 *
 * <h2>Reproducibility</h2>
 *
 * <p>Every run uses a <b>fixed seed</b>, so the suite is deterministic: a property that
 * passes in CI passes on your machine, and a failure in CI is reproducible locally without
 * hunting for the input. The seed is printed in the failure message anyway, because a
 * randomised property test whose counterexample cannot be re-run is a test that tells you
 * something is wrong and nothing about what.
 */
public final class Property {

    /**
     * The default number of cases per property.
     *
     * <p>A thousand is the figure doc 10 asks for. It is enough that a bug present in one
     * case in two hundred — a DST weekend, say — is found essentially every run, and small
     * enough that the whole suite stays under a second.
     */
    public static final int DEFAULT_TRIES = 1000;

    private Property() {
    }

    /**
     * Checks {@code assertion} against {@code tries} generated samples.
     *
     * <p>On the first failure, shrinks and reports. Execution stops there rather than
     * collecting every failure: a hundred counterexamples of the same bug is a hundred
     * copies of one piece of information.
     *
     * @param name      what is being asserted, in the failure message
     * @param seed      fixed per property, so runs are reproducible
     * @param generator produces a sample from a {@link Random}
     * @param shrinker  simpler candidates to try, in increasing order of simplicity; may
     *                  return an empty list, in which case the raw counterexample is
     *                  reported
     * @param assertion throws (any exception) when the property does not hold
     */
    public static <T> void forAll(String name, int tries, long seed,
                                  Function<Random, T> generator,
                                  Function<T, List<T>> shrinker,
                                  Consumer<T> assertion) {
        Random random = new Random(seed);
        for (int i = 0; i < tries; i++) {
            T sample = generator.apply(random);
            Throwable failure = check(assertion, sample);
            if (failure == null) {
                continue;
            }
            T smallest = shrink(sample, shrinker, assertion);
            Throwable smallestFailure = check(assertion, smallest);
            fail(("""
                    Property "%s" failed after %d case(s).

                      counterexample : %s
                      shrunk to      : %s
                      cause          : %s

                    Reproduce with seed %d.""")
                    .formatted(name, i + 1, sample, smallest,
                            smallestFailure == null ? failure : smallestFailure, seed),
                    smallestFailure == null ? failure : smallestFailure);
        }
    }

    /** The common case: a fixed seed and the default number of tries. */
    public static <T> void forAll(String name, long seed, Function<Random, T> generator,
                                  Function<T, List<T>> shrinker, Consumer<T> assertion) {
        forAll(name, DEFAULT_TRIES, seed, generator, shrinker, assertion);
    }

    /**
     * Greedy shrinking: repeatedly replace the counterexample with the first simpler
     * candidate that still fails, until none does.
     *
     * <p>Greedy rather than exhaustive, deliberately. The goal is a counterexample a human
     * can read — "Friday 17:58 plus 3 minutes" rather than "2026-04-17T13:42:19.318Z plus
     * 3,847 minutes" — and the first local minimum is almost always readable enough. Search
     * quality here buys nothing; the bug is the same bug.
     */
    private static <T> T shrink(T failing, Function<T, List<T>> shrinker, Consumer<T> assertion) {
        T current = failing;
        // Bounded, so a shrinker that accidentally returns a larger candidate cannot loop
        // for ever and turn a failing test into a hanging build.
        for (int round = 0; round < 200; round++) {
            T next = null;
            for (T candidate : shrinker.apply(current)) {
                if (check(assertion, candidate) != null) {
                    next = candidate;
                    break;
                }
            }
            if (next == null) {
                return current;
            }
            current = next;
        }
        return current;
    }

    private static <T> Throwable check(Consumer<T> assertion, T sample) {
        try {
            assertion.accept(sample);
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    /** Halving shrink for a positive long: 5000 -> 2500 -> 1250 ... -> 1. */
    public static List<Long> halvings(long value) {
        List<Long> candidates = new ArrayList<>();
        for (long next = value / 2; next >= 1; next /= 2) {
            candidates.add(next);
            if (next == 1) {
                break;
            }
        }
        candidates.add(1L);
        return candidates;
    }
}
