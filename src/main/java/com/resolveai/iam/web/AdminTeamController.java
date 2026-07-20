package com.resolveai.iam.web;

import com.resolveai.common.security.IsAdmin;
import com.resolveai.iam.security.ResolvePrincipal;
import com.resolveai.iam.service.InviteService;
import com.resolveai.iam.service.TeamAdminService;
import com.resolveai.iam.web.dto.CreateInviteRequest;
import com.resolveai.iam.web.dto.CreateTeamRequest;
import com.resolveai.iam.web.dto.InviteResponse;
import com.resolveai.iam.web.dto.TeamMemberResponse;
import com.resolveai.iam.web.dto.TeamResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Settings "Team" tab's backing endpoints: teams, the member roster, and pending invites.
 * {@code @IsAdmin} at class level - nothing here is meaningful for any other role.
 */
@RestController
@RequestMapping("/api/v1/admin")
@IsAdmin
public class AdminTeamController {

    private final TeamAdminService teamAdminService;
    private final InviteService inviteService;

    public AdminTeamController(TeamAdminService teamAdminService, InviteService inviteService) {
        this.teamAdminService = teamAdminService;
        this.inviteService = inviteService;
    }

    @GetMapping("/teams")
    public List<TeamResponse> listTeams() {
        return teamAdminService.listTeams();
    }

    @PostMapping("/teams")
    @ResponseStatus(HttpStatus.CREATED)
    public TeamResponse createTeam(@Valid @RequestBody CreateTeamRequest request) {
        return teamAdminService.createTeam(request);
    }

    @GetMapping("/members")
    public List<TeamMemberResponse> listMembers() {
        return teamAdminService.listMembers();
    }

    @GetMapping("/invites")
    public List<InviteResponse> listInvites(@AuthenticationPrincipal ResolvePrincipal principal) {
        return inviteService.listInvites(principal);
    }

    @PostMapping("/invites")
    @ResponseStatus(HttpStatus.CREATED)
    public InviteResponse createInvite(@AuthenticationPrincipal ResolvePrincipal principal,
                                       @Valid @RequestBody CreateInviteRequest request) {
        return inviteService.createInvite(principal, request);
    }
}
