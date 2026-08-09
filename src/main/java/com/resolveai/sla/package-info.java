/**
 * Business-hours SLA clocks, their append-only segments, and the escalation ladder.
 *
 * <p><b>Owns:</b> {@code business_calendar}, {@code business_holiday}, {@code sla_policy}, {@code sla_record}, {@code sla_clock_segment}, {@code sla_escalation}, and the SLA poller.
 *
 * <p><b>May depend on:</b> {@code ticketing} <b>by id and by event only</b>; {@code iam}, {@code platform}, {@code common}.
 *
 * <p>Elapsed time is a {@code SUM()} over {@code RUNNING} segments - there is no stored counter anywhere, so there is no counter to drift. Exactly-once escalation is the partial unique index {@code uq_escalation_rung}, not poller cleverness.
 */
package com.resolveai.sla;
