package com.resolveai.ticketing.service;

import com.resolveai.common.error.ApiException;
import com.resolveai.common.error.ErrorCode;
import com.resolveai.iam.domain.AppUser;
import com.resolveai.iam.domain.Role;
import com.resolveai.iam.repository.AppUserRepository;
import com.resolveai.iam.security.ResolvePrincipal;
import com.resolveai.ticketing.domain.Ticket;
import com.resolveai.ticketing.repository.TicketRepository;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Who may see which ticket. <b>One rule, one place.</b>
 *
 * <p>The scoping table from doc 05 §3.2 appears here and in
 * {@code TicketQueryRepository}'s WHERE clause, and nowhere else. It has to exist twice —
 * once as a predicate against a loaded row, once as SQL against a set of them — but two
 * copies is the maximum, and {@code CrossTenantAccessTest} is what keeps them agreeing.
 *
 * <h2>404, never 403</h2>
 *
 * <p>A ticket in another tenant, or another customer's ticket, is {@code 404
 * TICKET_NOT_FOUND}. Returning {@code 403} would confirm the ticket exists, which turns
 * {@code GET /tickets/{id}} into an oracle: walk the id space, collect the 403s, and you
 * have a count of every tenant's tickets and a set of live ids to try elsewhere.
 *
 * <p>{@code 403} is reserved for the genuinely different case — you can see this resource,
 * but this <i>action</i> is not permitted for your role. A customer being told their own
 * ticket cannot be reassigned is a 403; a customer asking about someone else's is a 404.
 *
 * <h2>The tenant is not checked here</h2>
 *
 * <p>Deliberately. {@code @TenantId} has already appended the discriminator to the query, so
 * another tenant's ticket does not come back at all and the {@code findById} below returns
 * empty. Re-checking it in Java would suggest the filter is not trusted, and would go stale
 * the moment somebody wrote a native query — which is why the cross-tenant test asserts the
 * behaviour rather than the check.
 */
@Component
public class TicketAccess {

    private final TicketRepository tickets;
    private final AppUserRepository users;

    public TicketAccess(TicketRepository tickets, AppUserRepository users) {
        this.tickets = tickets;
        this.users = users;
    }

    /** The caller's own row, for the team id the token does not carry. */
    public AppUser caller(ResolvePrincipal principal) {
        return users.findById(principal.userId())
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND,
                        "The authenticated user no longer exists."));
    }

    /**
     * Loads a ticket the caller is allowed to see, or throws {@code 404}.
     *
     * <p>Note the order: load, then filter. Loading first costs one query for a ticket the
     * caller cannot see, and the alternative — building the visibility predicate into the
     * lookup — would produce the same 404 with less obvious code, and would still need the
     * team lookup.
     */
    public Ticket loadVisible(ResolvePrincipal principal, Long ticketId) {
        Ticket ticket = tickets.findById(ticketId)
                .filter(t -> isVisibleTo(principal, t))
                .orElseThrow(() -> new ApiException(ErrorCode.TICKET_NOT_FOUND,
                        "Ticket " + ticketId + " was not found."));
        return ticket;
    }

    /** The same, holding a row lock. Used by every mutation that has side effects. */
    public Ticket loadVisibleForUpdate(ResolvePrincipal principal, Long ticketId) {
        return tickets.findByIdForUpdate(ticketId)
                .filter(t -> isVisibleTo(principal, t))
                .orElseThrow(() -> new ApiException(ErrorCode.TICKET_NOT_FOUND,
                        "Ticket " + ticketId + " was not found."));
    }

    /**
     * The scoping table, as a predicate.
     *
     * <p>{@code AGENT} sees their own assignments <i>or</i> their team's queue — the "or" is
     * what lets an agent pick up unassigned work, and removing it would mean an agent could
     * only ever see tickets somebody else had already given them.
     */
    public boolean isVisibleTo(ResolvePrincipal principal, Ticket ticket) {
        return switch (principal.role()) {
            case ADMIN -> true;
            case TEAM_LEAD, AGENT -> {
                if (principal.role() == Role.AGENT
                        && ticket.getAssignee() != null
                        && Objects.equals(ticket.getAssignee().getId(), principal.userId())) {
                    yield true;
                }
                Long myTeam = teamIdOf(principal);
                yield myTeam != null && ticket.getTeam() != null
                        && Objects.equals(ticket.getTeam().getId(), myTeam);
            }
            case CUSTOMER -> Objects.equals(ticket.getRequester().getId(), principal.userId());
        };
    }

    /**
     * Requires {@code AGENT} or above, for an action rather than a resource.
     *
     * <p>This is the 403 case: the ticket is visible — a customer's own ticket is visible to
     * them — but the action is not theirs to take.
     */
    public void requireAgentOrAbove(ResolvePrincipal principal, String action) {
        if (!principal.isAtLeast(Role.AGENT)) {
            throw ApiException.forbidden("Only agents and above may " + action + ".");
        }
    }

    public Long teamIdOf(ResolvePrincipal principal) {
        return Optional.ofNullable(caller(principal).getTeam())
                .map(t -> t.getId())
                .orElse(null);
    }
}
