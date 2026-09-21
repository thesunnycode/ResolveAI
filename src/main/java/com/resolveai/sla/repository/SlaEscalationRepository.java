package com.resolveai.sla.repository;

import com.resolveai.sla.domain.SlaEscalation;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SlaEscalationRepository extends JpaRepository<SlaEscalation, Long> {

    List<SlaEscalation> findBySlaRecordIdOrderByRungAsc(Long slaRecordId);

    boolean existsBySlaRecordIdAndRung(Long slaRecordId, short rung);

    long countBySlaRecordId(Long slaRecordId);
}
