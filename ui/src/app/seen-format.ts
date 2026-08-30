/**
 * How a first/last-seen timestamp is written, everywhere it appears.
 *
 * <p>Minute precision, and fixed-width on purpose. The backend sends a
 * zone-less `LocalDateTime` (`local_timestamp_ms` in the Avro), which reaches
 * a template unformatted as 26 characters of raw ISO with microseconds —
 * `2026-05-04T15:49:26.782619`. That overflows the table lens's 190px metrics
 * rail, and it is simply unreadable as a date.
 *
 * <p>`yyyy-MM-dd` rather than the workbench list's `MMM d, yy` because
 * `.metric-val` is monospace and right-aligned: a month-name format is 1–2
 * characters wider in December than in May, so a date that fits in testing can
 * overflow in production. This one is always exactly 16.
 *
 * <p>Seconds and microseconds are dropped rather than shortened. Nothing reads
 * a first/last-seen boundary to sub-minute resolution, and the date-math range
 * filter that queries these is minute-grained at its finest.
 *
 * <p>Shared rather than restated. It began as a private field on
 * `SqlSummaryComponent` (sqlx-07q), which is why the SQL view was still
 * rendering the raw 26-character value long after the problem was understood:
 * the fix was not reachable from where it was needed.
 */
export const SEEN_FORMAT = 'yyyy-MM-dd HH:mm';
