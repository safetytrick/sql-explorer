/**
 * Grafana-style date math.
 *
 * Supported expressions:
 *   now                  current time
 *   now-6d, now+1h       offset from now (units: s m h d w M y)
 *   -6d                  shorthand for now-6d
 *   now/d, now-1d/d      round to the start of the unit (end when roundUp, for 'to' bounds)
 *   2026-07-01           absolute date (local; whole-day when roundUp)
 *   2026-07-01 14:30     absolute date-time ('T' separator also accepted)
 */

export type DateMathUnit = 's' | 'm' | 'h' | 'd' | 'w' | 'M' | 'y';

const ABSOLUTE = /^(\d{4})-(\d{2})-(\d{2})([T ](\d{2}):(\d{2})(:(\d{2}))?)?$/;
const RELATIVE_PART = /([+-])(\d+)([smhdwMy])|\/([smhdwMy])/g;

function addInterval(date: Date, amount: number, unit: DateMathUnit): Date {
  const result = new Date(date.getTime());
  switch (unit) {
    case 's': result.setSeconds(result.getSeconds() + amount); break;
    case 'm': result.setMinutes(result.getMinutes() + amount); break;
    case 'h': result.setHours(result.getHours() + amount); break;
    case 'd': result.setDate(result.getDate() + amount); break;
    case 'w': result.setDate(result.getDate() + amount * 7); break;
    case 'M': result.setMonth(result.getMonth() + amount); break;
    case 'y': result.setFullYear(result.getFullYear() + amount); break;
  }
  return result;
}

function startOf(date: Date, unit: DateMathUnit): Date {
  const result = new Date(date.getTime());
  switch (unit) {
    case 's': result.setMilliseconds(0); break;
    case 'm': result.setSeconds(0, 0); break;
    case 'h': result.setMinutes(0, 0, 0); break;
    case 'd': result.setHours(0, 0, 0, 0); break;
    case 'w': result.setHours(0, 0, 0, 0); result.setDate(result.getDate() - result.getDay()); break;
    case 'M': result.setHours(0, 0, 0, 0); result.setDate(1); break;
    case 'y': result.setHours(0, 0, 0, 0); result.setMonth(0, 1); break;
  }
  return result;
}

/** Last second of the unit containing date (second granularity — the API's precision). */
function endOf(date: Date, unit: DateMathUnit): Date {
  const next = addInterval(startOf(date, unit), 1, unit);
  next.setSeconds(next.getSeconds() - 1);
  return next;
}

function parseAbsolute(text: string, roundUp: boolean): Date | null {
  const match = ABSOLUTE.exec(text);
  if (!match) { return null; }
  const [, year, month, day, timePart, hour, minute, , second] = match;
  const date = new Date(
    Number(year), Number(month) - 1, Number(day),
    Number(hour ?? 0), Number(minute ?? 0), Number(second ?? 0));
  // reject rolled-over dates like 2026-02-31
  const valid = date.getFullYear() === Number(year)
    && date.getMonth() === Number(month) - 1
    && date.getDate() === Number(day);
  if (!valid) { return null; }
  // a bare date used as a 'to' bound means the whole day
  return roundUp && !timePart ? endOf(date, 'd') : date;
}

function parseRelative(text: string, now: Date, roundUp: boolean): Date | null {
  let rest = text;
  if (rest.startsWith('now')) {
    rest = rest.slice(3);
  } else if (!/^[+\-/]/.test(rest)) {
    return null; // bare words like 'yesterday' are not supported
  }
  let result = new Date(now.getTime());
  let index = 0;
  while (index < rest.length) {
    RELATIVE_PART.lastIndex = index;
    const match = RELATIVE_PART.exec(rest);
    if (!match || match.index !== index) { return null; }
    if (match[4]) {
      const unit = match[4] as DateMathUnit;
      result = roundUp ? endOf(result, unit) : startOf(result, unit);
    } else {
      const sign = match[1] === '-' ? -1 : 1;
      result = addInterval(result, sign * Number(match[2]), match[3] as DateMathUnit);
    }
    index = RELATIVE_PART.lastIndex;
  }
  return result;
}

/**
 * Resolve an expression to a Date; null when empty or invalid.
 * roundUp selects Grafana's 'to'-bound semantics: /unit rounds to the end of
 * the unit and bare dates cover their whole day.
 */
export function parseDateExpr(expr: string | null | undefined, now = new Date(), roundUp = false): Date | null {
  const text = expr?.trim();
  if (!text) { return null; }
  return parseAbsolute(text, roundUp) ?? parseRelative(text, now, roundUp);
}

/** True for empty expressions and any expression parseDateExpr understands. */
export function isValidDateExpr(expr: string | null | undefined): boolean {
  return !expr?.trim() || parseDateExpr(expr) !== null;
}

const pad = (value: number) => String(value).padStart(2, '0');

/** Format as local YYYY-MM-DD. */
export function toIsoDate(date: Date | null): string | null {
  if (!date) { return null; }
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

/** Format as local YYYY-MM-DDTHH:mm:ss (the shape the search API expects). */
export function toIsoDateTime(date: Date | null): string | null {
  if (!date) { return null; }
  return `${toIsoDate(date)}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

/**
 * Resolve an expression straight to a local YYYY-MM-DDTHH:mm:ss string;
 * null when empty/invalid. Use roundUp for 'to'/before bounds.
 */
export function resolveDateExpr(expr: string | null | undefined, roundUp = false, now = new Date()): string | null {
  return toIsoDateTime(parseDateExpr(expr, now, roundUp));
}

/** Human-readable resolution of an expression, for previews. */
export function describeDateExpr(expr: string | null | undefined, roundUp = false, now = new Date()): string {
  const text = expr?.trim();
  if (!text) { return ''; }
  const date = parseDateExpr(text, now, roundUp);
  if (!date) { return 'invalid expression'; }
  const hasTime = date.getHours() !== 0 || date.getMinutes() !== 0 || date.getSeconds() !== 0;
  return date.toLocaleString(undefined, {
    year: 'numeric', month: 'short', day: 'numeric',
    ...(hasTime ? { hour: '2-digit', minute: '2-digit' } : {}),
  });
}
