package com.resolveai.iam.service;

import com.resolveai.iam.domain.AgentProfile;
import com.resolveai.iam.domain.AppUser;
import com.resolveai.iam.domain.Role;
import com.resolveai.iam.repository.AgentProfileRepository;
import com.resolveai.iam.repository.AppUserRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who is on the support side of this tenant: agents (with their load) and team leads.
 *
 * <p>Two UI needs, one read: the "Assign to…" menu on a ticket (which only lists people
 * with an agent profile, because assignment requires one) and naming the team leads who
 * can confirm a proposed incident, instead of telling an agent to wait for "a team lead".
 * Names, teams and load are not secret among staff; emails are not included.
 */
@Service
public class StaffDirectoryService {

    public record StaffMember(Long id, String fullName, Role role, String teamName,
                              Integer openCount, Integer maxConcurrent, Boolean available) {
    }

    private final AppUserRepository users;
    private final AgentProfileRepository profiles;

    public StaffDirectoryService(AppUserRepository users, AgentProfileRepository profiles) {
        this.users = users;
        this.profiles = profiles;
    }

    @Transactional(readOnly = true)
    public List<StaffMember> list() {
        List<StaffMember> out = new ArrayList<>();
        for (Role role : List.of(Role.AGENT, Role.TEAM_LEAD)) {
            for (AppUser u : users.findByRole(role)) {
                if (!u.isActive()) {
                    continue;
                }
                AgentProfile p = profiles.findByUserId(u.getId()).orElse(null);
                out.add(new StaffMember(u.getId(), u.getFullName(), u.getRole(),
                        u.getTeam() == null ? null : u.getTeam().getName(),
                        p == null ? null : p.getOpenCount(),
                        p == null ? null : p.getMaxConcurrent(),
                        p == null ? null : p.isAvailable()));
            }
        }
        out.sort(Comparator.comparing(StaffMember::role).thenComparing(StaffMember::fullName));
        return out;
    }
}
