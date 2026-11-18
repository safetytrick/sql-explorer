import type {ParamMap, Params} from '@angular/router';
import type {GlossaryTerm} from '../glossary';

/**
 * The server comparison (sqlx-a9h.2): the workbench's search, compared across
 * one cluster's servers. Response types mirror `topology/ServerComparison.java`.
 */

export type CompareFlag = 'ON_PRIMARY' | 'SKEWED' | 'SINGLE_SERVER' | 'PLAN_DIFFERS' | 'LATENCY_DIFFERS'
  | 'REPLICA_WRITES';
export type CompareBy = 'QUERY' | 'TABLE';
export type CompareSort = 'DIFFERENCE' | 'TIME';

export interface CompareColumn {
  name: string;
  roles: string[];
  read: boolean;
  windowDays: number | null;
}

export interface CompareCell {
  executions: number;
  seconds: number;
  secondsPerDay: number;
  executionsPerDay: number;
  rowsExaminedPerExecution: number;
  msPerExecution: number;
  rowsAffected: number;
}

export interface CompareRow {
  key: string;
  text: string;
  kind: string | null;
  flags: CompareFlag[];
  difference: number;
  secondsPerDay: number;
  cells: (CompareCell | null)[];
  queries: number;
}

export interface CompareReport {
  environment: string;
  clusters: string[];
  cluster: string | null;
  servers: CompareColumn[];
  by: CompareBy;
  matched: number;
  compared: number;
  flagCounts: Partial<Record<CompareFlag, number>>;
  unflagged: number;
  total: number;
  rows: CompareRow[];
  basis: string;
}

/** Every flag, in the order the badges and the filter read. */
export const FLAGS: CompareFlag[] =
  ['ON_PRIMARY', 'SKEWED', 'SINGLE_SERVER', 'PLAN_DIFFERS', 'LATENCY_DIFFERS', 'REPLICA_WRITES'];

export const FLAG_LABELS: Record<CompareFlag, string> = {
  ON_PRIMARY: 'read on primary',
  SKEWED: 'uneven',
  SINGLE_SERVER: 'one server',
  PLAN_DIFFERS: 'plan differs',
  LATENCY_DIFFERS: 'latency differs',
  REPLICA_WRITES: 'replica writes'
};

export const FLAG_TERMS: Record<CompareFlag, GlossaryTerm> = {
  ON_PRIMARY: 'flagOnPrimary',
  SKEWED: 'flagSkewed',
  SINGLE_SERVER: 'flagSingleServer',
  PLAN_DIFFERS: 'flagPlanDiffers',
  LATENCY_DIFFERS: 'flagLatencyDiffers',
  REPLICA_WRITES: 'flagReplicaWrites'
};

/** In the URL a flag is spelled lowercase with dashes: `plan-differs`. */
const flagParam = (flag: CompareFlag) => flag.toLowerCase().replace(/_/g, '-');
const flagFromParam = (text: string) => FLAGS.find(flag => flagParam(flag) === text.trim().toLowerCase());

/** The workbench view that shows the comparison in place of the list. */
export const SERVERS_VIEW = 'servers';

/** The params the comparison owns, which the workbench keeps while it shows. */
export const COMPARE_PARAMS = ['var-cluster', 'var-rows', 'var-flags', 'var-all', 'var-sort'] as const;

/**
 * Page state, all of it in the URL, defaults omitted: `var-cluster` (absent:
 * the busiest), `var-rows=table`, `var-flags=plan-differs,skewed`, `var-all=1`,
 * `var-sort=time`.
 */
export interface CompareState {
  cluster: string | null;
  by: CompareBy;
  flags: CompareFlag[];
  all: boolean;
  sort: CompareSort;
}

export function decodeCompareParams(params: Pick<ParamMap, 'get'>): CompareState {
  const flags = (params.get('var-flags') ?? '').split(',')
    .map(flagFromParam)
    .filter((flag): flag is CompareFlag => !!flag);
  return {
    cluster: params.get('var-cluster') || null,
    by: params.get('var-rows') === 'table' ? 'TABLE' : 'QUERY',
    flags: FLAGS.filter(flag => flags.includes(flag)),
    all: params.get('var-all') === '1',
    sort: params.get('var-sort') === 'time' ? 'TIME' : 'DIFFERENCE'
  };
}

/** Null clears a param, so an untouched view keeps a clean URL. */
export function encodeCompareParams(state: CompareState): Params {
  return {
    'var-cluster': state.cluster || null,
    'var-rows': state.by === 'TABLE' ? 'table' : null,
    'var-flags': state.flags.length ? FLAGS.filter(flag => state.flags.includes(flag)).map(flagParam).join(',') : null,
    'var-all': state.all ? '1' : null,
    'var-sort': state.sort === 'TIME' ? 'time' : null
  };
}

/**
 * Which cells of a row its flags are about, by server index, so the eye
 * lands on them: the primary for a read there, the busiest replica for an
 * uneven spread, the one server it ran on, the server examining the most
 * rows or taking the longest, and the replicas changing rows.
 */
export function hotCells(row: CompareRow, servers: CompareColumn[]): Set<number> {
  const hot = new Set<number>();
  const present = row.cells.map((cell, index) => ({cell, index})).filter(entry => entry.cell !== null) as
    {cell: CompareCell; index: number}[];
  const isPrimary = (index: number) => servers[index]?.roles.includes('primary');
  const maxBy = (entries: typeof present, value: (cell: CompareCell) => number) => {
    if (!entries.length) { return; }
    hot.add(entries.reduce((best, entry) => value(entry.cell) > value(best.cell) ? entry : best).index);
  };
  for (const flag of row.flags) {
    switch (flag) {
      case 'ON_PRIMARY':
        present.filter(entry => isPrimary(entry.index)).forEach(entry => hot.add(entry.index));
        break;
      case 'SKEWED':
        maxBy(present.filter(entry => servers[entry.index]?.roles.includes('replica')), cell => cell.executionsPerDay);
        break;
      case 'SINGLE_SERVER':
        present.filter(entry => !isPrimary(entry.index)).forEach(entry => hot.add(entry.index));
        break;
      case 'PLAN_DIFFERS':
        maxBy(present, cell => cell.rowsExaminedPerExecution);
        break;
      case 'LATENCY_DIFFERS':
        maxBy(present, cell => cell.msPerExecution);
        break;
      case 'REPLICA_WRITES':
        present.filter(entry => !isPrimary(entry.index)
          && (entry.cell.rowsAffected > 0 || row.kind === 'WRITE')).forEach(entry => hot.add(entry.index));
        break;
    }
  }
  return hot;
}

/** A count as a reader wants it: 950, 12k, 3.4M. */
export function compact(value: number | null | undefined): string {
  if (value == null) { return '—'; }
  const abs = Math.abs(value);
  if (abs >= 1e6) { return (value / 1e6).toFixed(abs >= 1e7 ? 0 : 1) + 'M'; }
  if (abs >= 1e3) { return (value / 1e3).toFixed(abs >= 1e4 ? 0 : 1) + 'k'; }
  if (abs >= 10 || value === 0) { return value.toFixed(0); }
  return value.toFixed(1);
}

/** Milliseconds per execution: 0.4 ms, 12 ms, 3.1 s. */
export function formatMs(ms: number): string {
  if (ms >= 1000) { return (ms / 1000).toFixed(ms >= 10000 ? 0 : 1) + ' s'; }
  return ms.toFixed(ms >= 10 ? 0 : 1) + ' ms';
}
