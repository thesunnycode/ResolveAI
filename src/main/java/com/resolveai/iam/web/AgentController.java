package com.resolveai.iam.web;

import com.resolveai.common.security.IsAgentOrAbove;
import com.resolveai.iam.security.ResolvePrincipal;
import com.resolveai.iam.service.AgentProfileService;
import com.resolveai.iam.service.StaffDirectoryService;
import com.resolveai.iam.web.dto.AgentProfileResponse;
import com.resolveai.iam.web.dto.AvailabilityRequest;
import com.resolveai.iam.web.dto.CapacityRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two agent-profile endpoints from doc 05 section 3.8.
 *
 * <p>They exist in Phase 4 rather than later because the design trace found
 * {@code agent_profile.is_available} to be read on every routing decision and writable by
 * nobody - and because they give the method-security annotations a real caller to be
 * verified against instead of a hypothetical one.
 */
@RestController
public class AgentController {

    private final AgentProfileService agentProfiles;
    private final StaffDirectoryService staff;

    public AgentController(AgentProfileService agentProfiles, StaffDirectoryService staff) {
        this.agentProfiles = agentProfiles;
        this.staff = staff;
    }

    /** Agents (with load) and team leads, for "Assign to…" and incident hand-offs. */
    @GetMapping("/api/v1/agents")
    @IsAgentOrAbove
    public List<StaffDirectoryService.StaffMember> list() {
        return staff.list();
    }

    @PutMapping("/api/v1/agents/me/availability")
    public AgentProfileResponse setOwnAvailability(
            @AuthenticationPrincipal ResolvePrincipal principal,
            @Valid @RequestBody AvailabilityRequest request) {
        return agentProfiles.updateOwnAvailability(principal, request);
    }

    @PutMapping("/api/v1/admin/agents/{userId}/capacity")
    public AgentProfileResponse setCapacity(
            @AuthenticationPrincipal ResolvePrincipal principal,
            @PathVariable Long userId,
            @Valid @RequestBody CapacityRequest request) {
        return agentProfiles.setCapacity(principal, userId, request);
    }
}
