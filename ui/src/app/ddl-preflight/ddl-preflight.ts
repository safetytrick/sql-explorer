/**
 * The DDL pre-flight check's response (sqlx-n5j.11), its URL codec and the
 * labels it shows. Pure, so the page's state can be checked without rendering.
 */

export const PREFLIGHT_ENDPOINT = '/api/sql-explorer/ddl/preflight';

export type Algorithm = 'INSTANT' | 'INPLACE' | 'INPLACE_REBUILD' | 'COPY' | 'UNKNOWN';
export type Severity = 'OK' | 'INFO' | 'WARN' | 'BLOCK';

export interface PreflightCheck { severity: Severity; title: string; detail: string; }

export interface ClauseVerdict {
  text: string;
  kind: string;
  algorithm: Algorithm;
  rebuildsTable: boolean;
  concurrentDml: boolean;
  reason: string;
}

export interface TableFacts {
  known: boolean;
  rowsEstimate: number | null;
  sizeBytes: number | null;
  rowFormat: string | null;
  declaredCollation: string | null;
  servers: number;
  childOfForeignKey: boolean;
  referencedBy: string[];
}

export interface Workload {
  writesPerDay: number;
  rowsChangedPerDay: number;
  longThresholdMs: number;
  longStatements: number;
  longExecutionsPerDay: number;
  maxTimeMs: number;
}

/** What MySQL would do with one ALGORITHM= on this statement. */
export type ChoiceOutcome = 'CHOSEN' | 'NEEDED' | 'SLOWER' | 'REFUSED' | 'UNPREDICTED';

export interface AlgorithmChoice {
  /** DEFAULT (none stated), INSTANT, INPLACE or COPY. */
  algorithm: string;
  outcome: ChoiceOutcome;
  requested: boolean;
  implication: string;
}

export function choiceOutcomeLabel(outcome: ChoiceOutcome): string {
  switch (outcome) {
    case 'CHOSEN': return 'MySQL decides';
    case 'NEEDED': return 'what it needs';
    case 'SLOWER': return 'accepted, slower';
    case 'REFUSED': return 'refused';
    default: return 'not predicted';
  }
}

export interface Preflight {
  statement: string;
  error: string | null;
  schema: string | null;
  table: string | null;
  clauses: ClauseVerdict[];
  algorithm: Algorithm;
  rebuildsTable: boolean;
  concurrentDml: boolean;
  requestedAlgorithm: string | null;
  requestedLock: string | null;
  tableFacts: TableFacts | null;
  workload: Workload | null;
  checks: PreflightCheck[];
  recommendations: string[];
  unknowns: string[];
  /** Absent from responses older than the field. */
  choices?: AlgorithmChoice[];
}

/** The page's state in the link: the statement and the long-statement threshold. */
export interface PreflightUrlState { ddl: string; longSeconds: number; }

export const DEFAULT_LONG_SECONDS = 1;
const DDL = 'var-ddl';
const LONG = 'var-long';

export function encodePreflightParams(state: PreflightUrlState): Record<string, string> {
  const params: Record<string, string> = {};
  if (state.ddl.trim()) { params[DDL] = state.ddl.trim(); }
  if (state.longSeconds !== DEFAULT_LONG_SECONDS) { params[LONG] = String(state.longSeconds); }
  return params;
}

export function decodePreflightParams(params: { get(name: string): string | null }): PreflightUrlState {
  const long = Number(params.get(LONG));
  return { ddl: params.get(DDL) ?? '', longSeconds: long > 0 ? long : DEFAULT_LONG_SECONDS };
}

/** How each algorithm reads on the page: what it means for the application. */
export function algorithmLabel(algorithm: Algorithm): string {
  switch (algorithm) {
    case 'INSTANT': return 'INSTANT · metadata only';
    case 'INPLACE': return 'INPLACE · writes continue';
    case 'INPLACE_REBUILD': return 'INPLACE · rebuilds the table, writes continue';
    case 'COPY': return 'COPY · blocks writes until done';
    default: return 'unknown';
  }
}

/** The worst severity first, so what stops a change reads before what merely informs. */
export function sortChecks(checks: PreflightCheck[]): PreflightCheck[] {
  const order: Severity[] = ['BLOCK', 'WARN', 'INFO', 'OK'];
  return [...checks].sort((a, b) => order.indexOf(a.severity) - order.indexOf(b.severity));
}
