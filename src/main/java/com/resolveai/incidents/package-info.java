/**
 * Correlation of ticket storms into incidents, and fan-out of updates to affected customers.
 *
 * <p><b>Owns:</b> {@code incident}, {@code incident_ticket}, {@code incident_update}, {@code incident_update_delivery}, the correlation gate and the fan-out worker.
 *
 * <p><b>May depend on:</b> {@code ticketing} by id and event; {@code sla} through an interface, to pause resolution clocks; {@code platform}; {@code common}.
 *
 * <p><b>A deterministic gate decides that an incident exists</b> - cluster size and arrival rate against a baseline. The model writes the title and nothing else. Fan-out is N individually-retryable deliveries guarded by {@code uq_delivery}, so a redelivered outbox event cannot double-message a customer.
 */
package com.resolveai.incidents;
