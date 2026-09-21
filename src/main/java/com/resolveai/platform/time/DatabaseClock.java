package com.resolveai.platform.time;

import java.time.OffsetDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * "Now", as Postgres sees it.
 *
 * <h2>Why not {@code OffsetDateTime.now()}</h2>
 *
 * <p>Every SLA deadline is a comparison between a timestamp the application <i>wrote</i>
 * and a timestamp something else <i>reads</i> — the poller asking whether a clock is due,
 * the at-risk queue asking how close one is, the resolve path asking whether the target
 * was missed. If those two timestamps come from different clocks, the answer depends on
 * the skew between them.
 *
 * <p>That is not a theoretical concern and it does not announce itself. It cost an
 * afternoon in Phase 5: a rung whose deadline had been computed from the JVM's clock was
 * simply never claimed by a query comparing against the database's, because a
 * containerised Postgres was running a second or two behind its host. The clock that had
 * "escalated at 50%" then sat there doing nothing, and the symptom — "the poller sometimes
 * misses one" — points nowhere near the cause.
 *
 * <p>With two application instances the same problem appears between <i>them</i>: the same
 * record is due on one node and not on the other, and which one wins depends on whose NTP
 * drifted. One clock, shared by everyone, removes the question. Postgres is the only thing
 * every instance already agrees on.
 *
 * <h2>Inside a transaction this is the transaction's start time</h2>
 *
 * <p>{@code NOW()} is {@code transaction_timestamp()}, so every call within one unit of
 * work returns the same instant. That is the wanted behaviour, not a caveat: a pause that
 * closes one segment at 10:04:01.7 and opens the next at 10:04:01.9 leaves a 200ms hole in
 * a clock that is supposed to be continuous.
 *
 * <h2>What still uses the JVM clock, and why that is fine</h2>
 *
 * <p>Token expiry in {@code iam}. A JWT's {@code exp} is compared against the clock of
 * whoever validates it — including clients — so anchoring it to the database would buy
 * nothing, and the tolerance there is minutes rather than milliseconds.
 */
@Component
public class DatabaseClock {

    private final JdbcTemplate jdbc;

    public DatabaseClock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The current instant according to the database.
     *
     * <p>One round trip, on a connection the caller's transaction already holds. It is a
     * cheaper query than any of the reads that follow it, and it is the only way to make
     * "is this due?" have one answer across every instance.
     */
    public OffsetDateTime now() {
        return jdbc.queryForObject("SELECT NOW()", OffsetDateTime.class);
    }
}
