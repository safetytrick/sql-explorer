/**
 * Column data profiles (sqlx-n5j.3): the response types, the URL state and
 * the small pure helpers the page shows them with.
 */

export const PROFILE_ENDPOINT = '/api/sql-explorer/profile/';

export type ProfileMethod = 'WINDOWS' | 'ENDS' | 'FULL_SCAN' | 'NONE';
export type RunStatus = 'running' | 'done' | 'failed' | 'cancelled';

export interface TargetView {
  cluster: string | null;
  server: string | null;
  rowsEstimate: number | null;
  available: boolean;
  reason: string | null;
  maxRows: number;
  maxStatements: number;
}

export interface ColumnView {
  name: string;
  type: string;
  characterSet: string | null;
  collation: string | null;
  signatures: string[];
}

/** What a run would do; shown, and agreed to, before it runs. */
export interface ProfileRequestPlan {
  environment: string;
  schema: string;
  table: string;
  method: ProfileMethod;
  key: string[];
  columns: ColumnView[];
  fullScanColumn: string | null;
  probeServer: string | null;
  unavailable: string | null;
  targets: TargetView[];
  /** As they will be sent; keys read during the run show as :key. */
  statements: string[];
  /** -1 when it cannot be bounded (a full scan of a table with no row estimate). */
  maxRows: number;
  maxStatements: number;
  statementTimeLimitMs: number;
  pauseMs: number;
  idsPerSignature: number;
  windows: string;
}

export interface RunSummary {
  id: number;
  environment: string;
  schema: string;
  table: string;
  probeServer: string | null;
  method: ProfileMethod;
  columns: string[];
  status: RunStatus;
  requestedBy: string | null;
  startedOn: string;
  finishedOn: string | null;
  rowsRead: number;
  statements: number;
  statementsSoFar: number;
  /** Finished more than 30 days ago: shown, not re-run. */
  stale: boolean;
}

export interface CharacterCount { matched: string; codePoints: string; intended: string | null; rows: number; }
export interface ServerIds { server: string; ids: string[]; }

export interface SignatureResult {
  signature: string;
  label: string;
  explanation: string;
  heuristic: boolean;
  informational: boolean;
  rows: number;
  ids: ServerIds[];
  characters: CharacterCount[];
}

export interface ColumnResult {
  column: string;
  type: string;
  characterSet: string | null;
  collation: string | null;
  rows: number;
  nulls: number;
  nonAscii: number;
  maxBytes: number | null;
  distinctAtLeast: number;
  signatures: SignatureResult[];
  verdict: string;
}

export interface WindowResult { label: string; from: string | null; to: string | null; rows: number; millis: number; error: string | null; }

export interface ServerResult {
  cluster: string | null;
  server: string;
  rowsEstimate: number | null;
  status: 'done' | 'failed' | 'cancelled' | 'skipped';
  reason: string | null;
  complete: boolean;
  coverage: string;
  windows: WindowResult[];
  rowsRead: number;
  statementCount: number;
  statements: string[];
  columns: ColumnResult[];
}

export interface ProfileResult {
  servers: ServerResult[];
  columns: ColumnResult[];
  confidence: 'sampled' | 'confirmed';
}

export interface RunDetail {
  run: RunSummary;
  plan: ProfileRequestPlan | null;
  result: ProfileResult | null;
  error: string | null;
}

/** The page's state in the link (all `var-`, all omitted at their defaults). */
export interface ProfileUrlState {
  /** The run shown; null for the latest. */
  run: number | null;
  /** One server to probe; null to sample every cluster. */
  server: string | null;
  /** The column expanded in the result. */
  column: string | null;
  /** The column a full scan is being planned for. */
  scan: string | null;
}

const RUN = 'var-run';
const SERVER = 'var-server';
const COLUMN = 'var-col';
const SCAN = 'var-scan';

export function decodeProfileParams(params: {get(name: string): string | null}): ProfileUrlState {
  const run = Number(params.get(RUN));
  return {
    run: Number.isInteger(run) && run > 0 ? run : null,
    server: params.get(SERVER) || null,
    column: params.get(COLUMN) || null,
    scan: params.get(SCAN) || null
  };
}

/** Every key present, null to remove it: for a `merge` navigation. */
export function encodeProfileParams(state: ProfileUrlState): Record<string, string | null> {
  return {
    [RUN]: state.run ? String(state.run) : null,
    [SERVER]: state.server || null,
    [COLUMN]: state.column || null,
    [SCAN]: state.scan || null
  };
}

/** The signatures worth showing: those that matched, problems before information. */
export function findings(column: ColumnResult): SignatureResult[] {
  return column.signatures.filter(s => s.rows > 0)
    .sort((a, b) => Number(a.informational) - Number(b.informational) || b.rows - a.rows);
}

/** A share as a percentage with one decimal, or a dash when there were no rows. */
export function share(part: number, whole: number): string {
  if (!whole) { return '—'; }
  const pct = part / whole * 100;
  return (pct > 0 && pct < 0.1 ? '<0.1' : pct.toFixed(pct >= 10 || pct === 0 ? 0 : 1)) + '%';
}

/** "≤ 20,000" style bound, or "unknown" for -1. */
export function bound(n: number): string {
  return n < 0 ? 'unknown' : n.toLocaleString('en-US');
}
