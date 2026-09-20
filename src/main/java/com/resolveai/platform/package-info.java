/**
 * Infrastructure every feature module borrows: the transactional outbox, idempotency, object storage, the AI gateway, and observability.
 *
 * <p><b>Owns:</b> {@code outbox_event}, {@code idempotency_record}, {@code pii_redaction_map}, {@code notification}, {@code prompt_version}, {@code tenant_ai_policy}, the MinIO client, and the Micrometer wiring.
 *
 * <p><b>May depend on:</b> {@code common} only.
 *
 * <p>The outbox lives here rather than in each feature module because <b>the dual-write problem is a property of the system, not of ticketing</b>. One poller, one dead-letter queue, one place to look when an event does not arrive.
 */
package com.resolveai.platform;
