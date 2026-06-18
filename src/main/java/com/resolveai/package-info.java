/**
 * ResolveAI - AI-powered helpdesk and incident triage.
 *
 * <p><b>The one architectural rule, recorded here because it is the one that erodes
 * first:</b> modules communicate through interfaces and outbox events, <b>never</b> by
 * referencing each other's entities.
 *
 * <p>Concretely, {@code com.resolveai.sla} must not import
 * {@code com.resolveai.ticketing.domain.Ticket}. It takes a {@code ticketId} and, where it
 * needs more, an interface that {@code ticketing} publishes. The moment one module reads
 * another's entity:
 *
 * <ul>
 *   <li>a lazy association crosses a transaction boundary that was never designed for it;
 *   <li>a column rename in one module breaks the compile of three others;
 *   <li>and the dependency becomes invisible - nothing in the build declares it, so nothing
 *       can stop it growing.
 * </ul>
 *
 * <p>This is enforced, not merely stated: the ArchUnit suite in Phase 9 fails the build on a
 * cross-module domain import. A rule written only in prose is a rule that has already been
 * broken somewhere you have not looked.
 *
 * <h2>Dependency direction</h2>
 *
 * <pre>
 *   common     &lt;- everything        (no dependencies of its own)
 *   platform   &lt;- everything        (outbox, idempotency, storage, AI gateway)
 *   iam        &lt;- everything        (identity and tenancy)
 *
 *   ticketing  -&gt; iam, platform, common
 *   sla        -&gt; ticketing (by id), iam, platform, common
 *   triage     -&gt; ticketing (by id), platform, common
 *   knowledge  -&gt; platform, common
 *   drafting   -&gt; knowledge, ticketing (by id), platform, common
 *   incidents  -&gt; ticketing (by id), sla (by interface), platform, common
 *   eval       -&gt; triage, drafting, incidents   (reads their outputs; nothing reads eval)
 * </pre>
 *
 * <p>There are no cycles in that list, and absence of cycles is the property ArchUnit
 * actually checks.
 */
package com.resolveai;
