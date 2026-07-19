package com.resolveai.iam.service;

import com.resolveai.common.error.ApiException;
import com.resolveai.common.error.ErrorCode;
import com.resolveai.common.security.IsAdmin;
import com.resolveai.common.security.IsAgentOrAbove;
import com.resolveai.iam.domain.AgentProfile;
import com.resolveai.iam.repository.AgentProfileRepository;
import com.resolveai.iam.security.ResolvePrincipal;
import com.resolveai.iam.web.dto.AgentProfileResponse;
import com.resolveai.iam.web.dto.AvailabilityRequest;
import com.resolveai.iam.web.dto.CapacityRequest;
import com.resolveai.platform.tenant.TenantScope;
import java.time.LocalTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Availability and capacity for agents.
 *
 * <p><b>The authorisation annotations are on these methods, not on the controller.</b> A
 * service called from a background worker, a scheduled job or another service never passes
 * through a controller, and a check that lives there would simply not run. Phases 6 to 8
 * add exactly those callers - the routing policy reads capacity on every assignment - so
 * annotating the service is what makes the guarantee hold everywhere rather than only over
 * HTTP.
 *
 * <p>This is also where the {@code @TenantId} discriminator earns its keep: neither method
 * filters by tenant, and neither can reach another tenant's profile, because the resolver
 * adds the predicate to the lookup.
 */
@Service
public class AgentProfileService {

    private static final Logger log = LoggerFactory.getLogger(AgentProfileService.class);

    private final AgentProfileRepository agentProfiles;
    private final TenantScope tenantScope;

    public AgentProfileService(AgentProfileRepository agentProfiles, TenantScope tenantScope) {
        this.agentProfiles = agentProfiles;
        this.tenantScope = tenantScope;
    }

    /**
     * An agent sets their <b>own</b> availability.
     *
     * <p>The target is taken from the authenticated principal, never from a parameter, so
     * there is no id to tamper with - a team lead cannot mark someone else unavailable here
     * even though the role check would let them call the method.
     *
     * <p>Going unavailable does <b>not</b> reassign existing tickets. It removes the agent
     * from future selection only, because silently moving someone's in-flight work is how a
     * queue loses the thread of who was doing what.
     */
    @IsAgentOrAbove
    public AgentProfileResponse updateOwnAvailability(ResolvePrincipal principal,
                                                      AvailabilityRequest request) {
        return tenantScope.inTenant(principal.tenantId(), () -> {
            AgentProfile profile = agentProfiles.findByUserId(principal.userId())
                    .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND,
                            "You do not have an agent profile."));

            profile.setAvailable(request.isAvailable());
            profile.setShift(parse(request.shiftStart()), parse(request.shiftEnd()));
            agentProfiles.saveAndFlush(profile);

            log.info("Agent {} is now {}", principal.userId(),
                    request.isAvailable() ? "available" : "unavailable");
            return AgentProfileResponse.from(profile, null);
        });
    }

    /**
     * An admin sets another agent's ceiling.
     *
     * <p>Lowering it below the agent's current {@code openCount} is permitted, and the
     * response says so rather than rejecting: the ceiling governs <i>new</i> assignment. A
     * validation error here would mean an admin cannot reduce the load on someone who is
     * already overloaded, which is exactly when they would want to.
     */
    @IsAdmin
    public AgentProfileResponse setCapacity(ResolvePrincipal principal, Long userId,
                                            CapacityRequest request) {
        return tenantScope.inTenant(principal.tenantId(), () -> {
            AgentProfile profile = agentProfiles.findByUserId(userId)
                    // 404 rather than 403 for an agent in another tenant: the profile is
                    // simply not visible, and saying "forbidden" would confirm it exists.
                    .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND,
                            "No agent profile for user " + userId + "."));

            String warning = request.maxConcurrent() < profile.getOpenCount()
                    ? "New ceiling is below the agent's current open count ("
                      + profile.getOpenCount() + "). No tickets were reassigned; the ceiling "
                      + "applies to new assignment only."
                    : null;

            profile.setMaxConcurrent(request.maxConcurrent());
            agentProfiles.saveAndFlush(profile);
            return AgentProfileResponse.from(profile, warning);
        });
    }

    private static LocalTime parse(String hhmm) {
        return hhmm == null ? null : LocalTime.parse(hhmm);
    }
}
