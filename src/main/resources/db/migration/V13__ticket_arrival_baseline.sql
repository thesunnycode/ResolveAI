-- V13__ticket_arrival_baseline.sql
-- Doc 12 Task 5 — the correlation gate's "is this rate normal?" denominator.
--
-- One row per (tenant, day-of-week, hour-of-day): the average number of tickets created in
-- that hour-of-week over the last 28 days, EXCLUDING the last 24 hours (a live storm must
-- not inflate its own baseline and suppress its own detection).
--
-- sample_weeks is not "how many tickets landed in this cell" — it is how many times this
-- (dow, hod) slot actually occurred within the tenant's available window, INCLUDING hours
-- with zero tickets. A tenant only three days old has a window bounded by its own
-- created_at, not by 28 days, so its Friday-14:00 slot may not have occurred at all yet
-- (no row here), or only once or twice — which is exactly the cold-start signal
-- BaselineService reads to choose SPECIFIC, GLOBAL_HOURLY or FLOOR.
--
-- DO NOT EDIT once applied: Flyway checksums this file.

CREATE MATERIALIZED VIEW ticket_arrival_baseline AS
WITH bounds AS (
    SELECT t.id AS tenant_id,
           GREATEST(t.created_at, NOW() - INTERVAL '28 days') AS window_start,
           NOW() - INTERVAL '24 hours' AS window_end
      FROM tenant t
),
hours AS (
    SELECT b.tenant_id, gs AS hour_slot
      FROM bounds b,
           LATERAL generate_series(
               date_trunc('hour', b.window_start),
               date_trunc('hour', b.window_end),
               INTERVAL '1 hour') AS gs
     WHERE b.window_start < b.window_end
),
occurrences AS (
    SELECT tenant_id,
           EXTRACT(DOW  FROM hour_slot)::int AS dow,
           EXTRACT(HOUR FROM hour_slot)::int AS hod,
           COUNT(*) AS sample_weeks
      FROM hours
     GROUP BY 1, 2, 3
),
actual AS (
    SELECT tenant_id,
           EXTRACT(DOW  FROM created_at)::int AS dow,
           EXTRACT(HOUR FROM created_at)::int AS hod,
           COUNT(*) AS ticket_count
      FROM ticket
     WHERE created_at >= NOW() - INTERVAL '28 days'
       AND created_at <  NOW() - INTERVAL '24 hours'
     GROUP BY 1, 2, 3
)
SELECT o.tenant_id, o.dow, o.hod,
       o.sample_weeks,
       (COALESCE(a.ticket_count, 0)::numeric / o.sample_weeks) AS baseline_count
  FROM occurrences o
  LEFT JOIN actual a
    ON a.tenant_id = o.tenant_id AND a.dow = o.dow AND a.hod = o.hod
WITH DATA;

-- Required for REFRESH MATERIALIZED VIEW CONCURRENTLY, which ArrivalBaselineRefreshJob
-- uses so the nightly refresh never blocks a sweep reading the previous night's data.
CREATE UNIQUE INDEX uq_arrival_baseline ON ticket_arrival_baseline(tenant_id, dow, hod);
