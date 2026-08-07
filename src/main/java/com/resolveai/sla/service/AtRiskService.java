package com.resolveai.sla.service;

import com.resolveai.common.pagination.CursorPage;
import com.resolveai.iam.domain.Role;
import com.resolveai.iam.security.ResolvePrincipal;
import com.resolveai.sla.domain.CalendarSpec;
import com.resolveai.sla.domain.SlaKind;
import com.resolveai.sla.domain.SlaRecord;
import com.resolveai.sla.repository.SlaRecordRepository;
import com.resolveai.sla.web.dto.AtRiskRow;
import com.resolveai.sla.web.dto.SlaResponse;
import com.resolveai.ticketing.domain.Ticket;
import com.resolveai.ticketing.service.TicketAccess;
import com.resolveai.ticketing.web.dto.UserRef;
import com.resolveai.platform.time.DatabaseClock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The queue of clocks that are going to breach, ranked.
 *
 * <p>Two ways in: the prediction says so ({@code predicted > remaining}), or the deadline
 * is inside the window regardless of prediction. The second matters because a tenant with
 * no history has no prediction at all, and "no data yet" must not mean "no warnings" —
 * that would make the feature useless for exactly the first ninety days when somebody is
 * deciding whether to keep using it.
 *
 * <h2>{@code reason} is not decoration</h2>
 *
 * <p>Every row carries a sentence a human can check: <i>"Predicted resolution (180 min,
 * p75 for this class) exceeds remaining budget (34 min)"</i>. A ranked list with no
 * explanation is a list agents stop opening after the third time it was wrong, and an
 * at-risk queue nobody opens is worse than none — it creates the impression the risk is
 * being managed.
 *
 * <h2>Ranking is in memory, and that is a choice with a limit</h2>
 *
 * <p>Risk depends on elapsed business minutes, which depends on the segment walk, which
 * SQL cannot do. So the running records are loaded, scored, sorted and paged here. That is
 * fine at this scale — the input is running clocks whose deadline is near, not all open
 * tickets — and it stops being fine somewhere in the low thousands per tenant. The fix at
 * that point is a materialised risk score updated by the poller, and it is a bigger change
 * than it looks because the score would then be stale between polls.
 */
@Service
public class AtRiskService {

    private static final int DEFAULT_WINDOW_MINUTES = 120;

    private final SlaRecordRepository records;
    private final SlaCalculator calculator;
    private final CalendarService calendars;
    private final BreachPredictor predictor;
    private final TicketAccess access;
    private final DatabaseClock clock;

    public AtRiskService(SlaRecordRepository records, SlaCalculator calculator,
                         CalendarService calendars, BreachPredictor predictor,
                         TicketAccess access, DatabaseClock clock) {
        this.records = records;
        this.calculator = calculator;
        this.calendars = calendars;
        this.predictor = predictor;
        this.access = access;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CursorPage<AtRiskRow> atRisk(ResolvePrincipal principal, Long teamId,
                                        Integer withinBusinessMinutes, int size) {
        int window = withinBusinessMinutes == null
                ? DEFAULT_WINDOW_MINUTES : withinBusinessMinutes;
        CalendarSpec calendar = calendars.current();
        OffsetDateTime now = clock.now();

        List<AtRiskRow> rows = new ArrayList<>();
        for (SlaRecord record : records.findRunning()) {
            Ticket ticket = record.getTicket();
            // Role scoping, the same rule the ticket queue applies. An at-risk list that
            // ignored it would be a way for any agent to enumerate every ticket in the
            // tenant, filtered to the interesting ones.
            if (!access.isVisibleTo(principal, ticket)) {
                continue;
            }
            if (teamId != null && (ticket.getTeam() == null
                    || !Objects.equals(ticket.getTeam().getId(), teamId))) {
                continue;
            }
            if (principal.role() == Role.CUSTOMER) {
                continue;
            }

            long elapsed = calculator.elapsedBusinessMinutes(record.getId(), calendar, now);
            long remaining = record.getTargetMinutes() - elapsed;
            Optional<SlaResponse.PredictionView> prediction =
                    predictor.predict(record, calendar, now);

            boolean predictedBreach = prediction.map(SlaResponse.PredictionView::atRisk)
                    .orElse(false);
            boolean deadlineNear = remaining <= window;
            if (!predictedBreach && !deadlineNear) {
                continue;
            }

            rows.add(toRow(record, ticket, remaining, prediction, predictedBreach, window));
        }

        rows.sort(Comparator.comparingDouble(AtRiskRow::riskScore).reversed()
                .thenComparing(AtRiskRow::ticketId));

        List<AtRiskRow> page = rows.size() > size ? rows.subList(0, size) : rows;
        return new CursorPage<>(List.copyOf(page),
                // No cursor: the set is scored in memory and re-scored on every call, so
                // a keyset cursor over it would not be stable anyway. Saying hasNext
                // without offering a cursor is the honest shape - the client should
                // narrow the window, not page through a ranking that has already moved.
                new CursorPage.PageInfo(size, null, rows.size() > size));
    }

    private AtRiskRow toRow(SlaRecord record, Ticket ticket, long remaining,
                            Optional<SlaResponse.PredictionView> prediction,
                            boolean predictedBreach, int window) {
        Long predicted = prediction
                .map(SlaResponse.PredictionView::predictedResolutionBusinessMinutes)
                .orElse(null);

        String reason;
        if (predictedBreach) {
            reason = "Predicted resolution (%d min, %s) exceeds remaining budget (%d min)"
                    .formatted(predicted, prediction.get().basis(), remaining);
        } else if (remaining < 0) {
            reason = "Past target by %d business minutes".formatted(-remaining);
        } else {
            reason = "Deadline is within %d business minutes (%d remaining)"
                    .formatted(window, remaining);
        }

        return new AtRiskRow(ticket.getId(), ticket.getReference(), ticket.getSubject(),
                ticket.getPriority().name(), UserRef.of(ticket.getAssignee()),
                record.getKind().name(), remaining, predicted,
                riskScore(record, remaining, predicted), reason,
                record.getNextDeadlineAt() == null ? null
                        : record.getNextDeadlineAt().toString());
    }

    /**
     * A number in [0, 1] whose <b>only</b> job is to order the list.
     *
     * <p>Deliberately not presented as a probability anywhere in the UI. It is not
     * calibrated against anything, and showing "94% likely to breach" would claim a
     * precision this does not have. Ranking is a much weaker claim and is all the feature
     * needs: the agent works down the list.
     *
     * <p>Composed of how much budget is gone and how far the prediction overshoots, with
     * a first-response clock weighted up — a missed first response is visible to the
     * customer immediately, where a resolution running late is not.
     */
    private double riskScore(SlaRecord record, long remaining, Long predicted) {
        double consumed = 1.0 - ((double) remaining / Math.max(1, record.getTargetMinutes()));
        double overshoot = predicted == null || remaining >= predicted ? 0.0
                : Math.min(1.0, (predicted - remaining) / (double) Math.max(1, predicted));
        double weight = record.getKind() == SlaKind.FIRST_RESPONSE ? 1.1 : 1.0;
        return Math.max(0.0, Math.min(1.0, (consumed * 0.6 + overshoot * 0.4) * weight));
    }
}
