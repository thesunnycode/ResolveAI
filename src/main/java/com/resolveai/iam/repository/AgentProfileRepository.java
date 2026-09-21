package com.resolveai.iam.repository;

import com.resolveai.iam.domain.AgentProfile;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentProfileRepository extends JpaRepository<AgentProfile, Long> {

    Optional<AgentProfile> findByUserId(Long userId);
}
