import {compactCount} from './compact-count';

/**
 * Formatting for information_schema.TABLES figures (sqlx-n5j.2). Row counts
 * there are estimates, so they are shown with a "≈" rather than as a count.
 */

/** 0 → "0 B", 163840 → "160 KiB", 5368709120 → "5.0 GiB"; null → "". */
export function formatBytes(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined) { return ''; }
  const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB', 'PiB'];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  const shown = unit === 0 || value >= 100 ? Math.round(value).toString() : value.toFixed(1);
  return shown + ' ' + units[unit];
}

/** An estimate, marked as one: 1583066 → "≈1.6M"; null → "". */
export function formatRowsEstimate(rows: number | null | undefined): string {
  if (rows === null || rows === undefined) { return ''; }
  return '≈' + compactCount(rows);
}
