import { isValidDateExpr, parseDateExpr, resolveDateExpr, toIsoDate, toIsoDateTime } from './date-math';

describe('date-math', () => {
  // Wed Jul 29 2026 15:30:00 local
  const now = new Date(2026, 6, 29, 15, 30, 0);

  it('parses now', () => {
    expect(parseDateExpr('now', now)!.getTime()).toBe(now.getTime());
  });

  it('parses now-6d', () => {
    expect(toIsoDate(parseDateExpr('now-6d', now))).toBe('2026-07-23');
  });

  it('parses -6d shorthand', () => {
    expect(toIsoDate(parseDateExpr('-6d', now))).toBe('2026-07-23');
  });

  it('keeps hour precision', () => {
    expect(resolveDateExpr('now-16h', false, now)).toBe('2026-07-28T23:30:00');
    expect(resolveDateExpr('now-6d', false, now)).toBe('2026-07-23T15:30:00');
  });

  it('parses weeks, months, and years', () => {
    expect(toIsoDate(parseDateExpr('now-2w', now))).toBe('2026-07-15');
    expect(toIsoDate(parseDateExpr('now-6M', now))).toBe('2026-01-29');
    expect(toIsoDate(parseDateExpr('now-1y', now))).toBe('2025-07-29');
  });

  it('rounds down with /unit', () => {
    expect(resolveDateExpr('now/d', false, now)).toBe('2026-07-29T00:00:00');
    expect(resolveDateExpr('now/M', false, now)).toBe('2026-07-01T00:00:00');
    expect(resolveDateExpr('now/y', false, now)).toBe('2026-01-01T00:00:00');
  });

  it('rounds up with /unit for to-bounds', () => {
    expect(resolveDateExpr('now/d', true, now)).toBe('2026-07-29T23:59:59');
    expect(resolveDateExpr('now/M', true, now)).toBe('2026-07-31T23:59:59');
    expect(resolveDateExpr('now-1M/M', true, now)).toBe('2026-06-30T23:59:59');
  });

  it('combines offsets and rounding', () => {
    expect(toIsoDate(parseDateExpr('now-1M/M', now))).toBe('2026-06-01');
    expect(toIsoDate(parseDateExpr('now-1d-12h', now))).toBe('2026-07-28');
  });

  it('parses absolute dates and date-times', () => {
    expect(resolveDateExpr('2026-07-01', false, now)).toBe('2026-07-01T00:00:00');
    expect(resolveDateExpr('2026-07-01 14:30', false, now)).toBe('2026-07-01T14:30:00');
    expect(resolveDateExpr('2026-07-01T14:30:45', false, now)).toBe('2026-07-01T14:30:45');
  });

  it('treats a bare date as the whole day when used as a to-bound', () => {
    expect(resolveDateExpr('2026-07-01', true, now)).toBe('2026-07-01T23:59:59');
    // an explicit time is respected as-is
    expect(resolveDateExpr('2026-07-01 14:30', true, now)).toBe('2026-07-01T14:30:00');
  });

  it('rejects garbage', () => {
    expect(parseDateExpr('yesterday', now)).toBeNull();
    expect(parseDateExpr('now-6', now)).toBeNull();
    expect(parseDateExpr('now-6q', now)).toBeNull();
    expect(parseDateExpr('2026-02-31', now)).toBeNull();
    expect(parseDateExpr('now-6d extra', now)).toBeNull();
  });

  it('treats empty as valid but unresolvable', () => {
    expect(isValidDateExpr('')).toBeTrue();
    expect(isValidDateExpr(null)).toBeTrue();
    expect(parseDateExpr('', now)).toBeNull();
    expect(resolveDateExpr('', false, now)).toBeNull();
    expect(toIsoDateTime(null)).toBeNull();
  });

  it('flags invalid expressions', () => {
    expect(isValidDateExpr('nope')).toBeFalse();
    expect(isValidDateExpr('now-6d')).toBeTrue();
    expect(isValidDateExpr('2026-07-01')).toBeTrue();
  });
});
