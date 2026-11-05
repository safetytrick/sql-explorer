/** Large counts, short: shared by the workbench badges and the environment status. */
/** 4885 → 4.9k, 1583066 → 1.6M; small numbers as they are. */
export function compactCount(value: number): string {
  if (value < 1000) { return String(value); }
  const [divisor, suffix] = value >= 1e9 ? [1e9, 'B'] : value >= 1e6 ? [1e6, 'M'] : [1e3, 'k'];
  const scaled = value / divisor;
  return (scaled >= 100 ? Math.round(scaled).toString() : scaled.toFixed(1).replace(/\.0$/, '')) + suffix;
}
