package com.resolveai.sla.web.dto;

import com.resolveai.ticketing.web.dto.UserRef;

/**
 * One row of the at-risk queue.
 *
 * @param riskScore 0-1, used only for ordering. Deliberately not shown as a percentage in
 *                  the UI: it is a ranking signal, and presenting it as a probability would
 *                  claim a calibration this does not have.
 * @param reason    a sentence a human can check, e.g. <i>"Predicted resolution (180 min,
 *                  p75 for this class) exceeds remaining budget (34 min)"</i>. <b>A ranked
 *                  list with no explanation is a list nobody trusts</b>, and an at-risk
 *                  queue that agents ignore is worse than no queue at all.
 */
public record AtRiskRow(
        Long ticketId,
        String reference,
        String subject,
        String priority,
        UserRef assignee,
        String clockKind,
        long remainingBusinessMinutes,
        Long predictedResolutionBusinessMinutes,
        double riskScore,
        String reason,
        String nextDeadlineAt) {
}
