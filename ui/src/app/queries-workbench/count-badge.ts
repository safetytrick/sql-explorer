/**
 * The error and warning badges on a workbench row (sqlx-ywy.7).
 *
 * They read `4885err` / `1583066warn`: a raw count glued to its word, with
 * nothing to say whether it is a lot. The label is now compact (`err 4.9k`)
 * and the hover gives the exact count and its rate against executions — the
 * same count means something different on a query run a hundred times and
 * one run three million times.
 */
import {compactCount} from '../compact-count';

export {compactCount};

export interface CountBadge { label: string; title: string; }

export function countBadge(word: 'err' | 'warn', count: number, executed: number): CountBadge {
  const noun = word === 'err' ? (count === 1 ? 'error' : 'errors') : (count === 1 ? 'warning' : 'warnings');
  const runs = `${executed.toLocaleString('en-US')} execution${executed === 1 ? '' : 's'}`;
  const rate = executed > 0 ? ` — ${perExecution(count / executed)}` : '';
  return {
    label: `${word} ${compactCount(count)}`,
    title: `${count.toLocaleString('en-US')} ${noun} across ${runs}${rate}`
  };
}

/** A rate per execution; warnings can exceed one per run, so it is not capped at 100%. */
function perExecution(rate: number): string {
  if (rate >= 1) { return `${rate.toFixed(rate >= 10 ? 0 : 1)} per execution`; }
  const pct = rate * 100;
  return pct < 0.01 ? 'under 0.01% of executions' : `${pct < 1 ? pct.toPrecision(2) : pct.toFixed(1)}% of executions`;
}
