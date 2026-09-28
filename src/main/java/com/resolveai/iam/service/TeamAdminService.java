package com.resolveai.iam.service;

import com.resolveai.iam.domain.AppUser;
import com.resolveai.iam.domain.Role;
import com.resolveai.iam.domain.Team;
import com.resolveai.iam.repository.AppUserRepository;
import com.resolveai.iam.repository.TeamRepository;
import com.resolveai.iam.web.dto.CreateTeamRequest;
import com.resolveai.iam.web.dto.TeamMemberResponse;
import com.resolveai.iam.web.dto.TeamResponse;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Team CRUD and the tenant's member roster for the Settings "Team" tab. Both {@link Team} and
 * {@link AppUser} are {@code @TenantId}-scoped, so - unlike {@link InviteService} - nothing
 * here needs an explicit {@code tenantId}: the request's own tenant context already filters
 * every query.
 */
@Service
public class TeamAdminService {

    private static final List<Role> TEAM_ROLES = List.of(Role.AGENT, Role.TEAM_LEAD, Role.ADMIN);

    private final TeamRepository teams;
    private final AppUserRepository users;

    public TeamAdminService(TeamRepository teams, AppUserRepository users) {
        this.teams = teams;
        this.users = users;
    }

    /** {@code @IsAdmin}, enforced by the controller. */
    public List<TeamResponse> listTeams() {
        return teams.findAllByOrderByNameAsc().stream().map(TeamResponse::from).toList();
    }

    /** {@code @IsAdmin}, enforced by the controller. */
    public TeamResponse createTeam(CreateTeamRequest request) {
        Team team = teams.save(new Team(request.name().trim(), request.skills(), false));
        return TeamResponse.from(team);
    }

    /** {@code @IsAdmin}, enforced by the controller. Customers are excluded on purpose. */
    public List<TeamMemberResponse> listMembers() {
        return users.findByRoleInOrderByFullNameAsc(TEAM_ROLES).stream()
                .map(TeamMemberResponse::from)
                .toList();
    }
}
