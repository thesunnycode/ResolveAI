package com.resolveai.triage.routing;

import com.resolveai.iam.domain.Team;
import com.resolveai.iam.repository.TeamRepository;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which team owns a ticket, given the category the model proposed.
 *
 * <h2>The model proposed a category; the routing is business configuration</h2>
 *
 * <p>These are two different kinds of fact and they belong in two different places. The
 * category is a reading of the ticket's text, which is what a language model is good at.
 * "Billing tickets go to the Revenue team this quarter" is a decision an operations lead
 * makes and changes, and it must be changeable by editing a team's {@code skills} array —
 * not by editing a prompt, re-running an eval suite and redeploying.
 *
 * <p>Put the routing in the prompt and every tenant shares one org chart, a tenant
 * reorganising means a prompt change for everybody, and the only record of who owns
 * billing is a paragraph of English inside a model call.
 *
 * <h2>Four steps, and the fourth is the one that matters</h2>
 *
 * <ol>
 *   <li>a team whose skills cover the category;
 *   <li>if several, the most specific — see {@code TeamRepository.findMostSpecificForSkill};
 *   <li>otherwise the tenant's default team;
 *   <li>otherwise <b>nothing</b>, and the ticket stays unrouted.
 * </ol>
 *
 * <p>Step four is a deliberate non-error. A tenant with no default team is
 * misconfigured, but that is not this ticket's fault, and failing triage over it would
 * dead-letter the event, leave no analysis and no priority, and turn a configuration gap
 * into lost work. An unrouted ticket is visible in the queue and a human can move it,
 * which is the correct outcome for a problem a human has to fix anyway.
 */
@Service
public class RoutingPolicy {

    private static final Logger log = LoggerFactory.getLogger(RoutingPolicy.class);

    private final TeamRepository teams;

    public RoutingPolicy(TeamRepository teams) {
        this.teams = teams;
    }

    /**
     * @param category the model's category, or {@code null} when triage produced none.
     *                 A null category is routed to the default team rather than refused:
     *                 a ticket nobody could classify still has to land somewhere, and
     *                 the default team is where a human looks.
     * @return the owning team, or empty for {@code UNROUTED}
     */
    @Transactional(readOnly = true)
    public Optional<Team> selectTeam(Long tenantId, String category) {
        if (category != null && !category.isBlank()) {
            Optional<Team> skilled = teams.findMostSpecificForSkill(tenantId, category);
            if (skilled.isPresent()) {
                return skilled;
            }
        }

        Optional<Team> fallback = teams.findByIsDefaultTrue();
        if (fallback.isEmpty()) {
            // WARN rather than silence: an unrouted ticket looks like an ordinary
            // untriaged one in the queue, so without this line the only signal that a
            // tenant has no default team is somebody eventually noticing.
            log.warn("Tenant {} has no team for category {} and no default team; "
                     + "ticket will be left unrouted", tenantId, category);
        }
        return fallback;
    }
}
