import {ServerSummary} from '../environment.service';
import {Preflight} from './ddl-preflight';

export const LOCK_HOLDERS_ENDPOINT = '/api/sql-explorer/ddl/lock-holders';

/** The URL parameter holding the chosen servers; read on open, never acted on alone. */
export const SERVERS_PARAM = 'var-servers';

export interface TableRef { schema: string; table: string; }

/** One metadata lock on one of the tables, and the session holding or awaiting it. */
export interface LockHolder {
  schema: string;
  table: string;
  lockType: string;
  duration: string;
  /** GRANTED, or PENDING when that session is itself waiting. */
  status: string;
  processlistId: number | null;
  user: string | null;
  host: string | null;
  command: string | null;
  state: string | null;
  /** Time in its current state: for an idle connection, since its last statement. */
  secondsInState: number | null;
  /** How long its transaction has been open; null with none, or when it could not be read. */
  transactionSeconds: number | null;
}

export interface LockHolderServer {
  server: string;
  roles: string[];
  error: string | null;
  note: string | null;
  holders: LockHolder[];
}

export interface LockHolderReport {
  environment: string;
  readAt: string;
  tookMillis: number;
  tables: TableRef[];
  servers: LockHolderServer[];
}

/**
 * The tables whose open transactions hold the change up: the table itself,
 * and each relative it locks exclusively. Relatives whose writes merely wait
 * are left out: anything they hold that matters is held on one of these.
 */
export function holderTables(result: Preflight | null): TableRef[] {
  if (!result?.schema || !result.table) { return []; }
  const tables: TableRef[] = [{schema: result.schema, table: result.table}];
  for (const t of result.lockedTables ?? []) {
    if (t.reach === 'EVERY_QUERY' && !tables.some(x => x.schema === t.schema && x.table === t.table)) {
      tables.push({schema: t.schema, table: t.table});
    }
  }
  return tables;
}

/** Preselected: each primary. Nothing runs until the button is pressed, so this only saves a click. */
export function defaultServers(servers: ServerSummary[]): string[] {
  return servers.filter(s => (s.roles ?? []).includes('primary')).map(s => s.name);
}

/** The chosen servers from the URL, kept only if the environment still has them; null when the URL says nothing. */
export function decodeServers(param: string | null, servers: ServerSummary[]): string[] | null {
  if (param === null) { return null; }
  const known = new Set(servers.map(s => s.name));
  return param.split(',').map(s => s.trim()).filter(s => known.has(s));
}

/** The URL value for a selection: null (omitted) when it is the default. Order follows the environment's. */
export function encodeServers(selected: string[], servers: ServerSummary[]): string | null {
  const ordered = servers.map(s => s.name).filter(name => selected.includes(name));
  const defaults = defaultServers(servers);
  const same = ordered.length === defaults.length && ordered.every((name, i) => name === defaults[i]);
  return same ? null : ordered.join(',');
}

/** Seconds as a short age: 45s, 12m 05s, 3h 02m. */
export function fmtAge(seconds: number | null): string {
  if (seconds === null || seconds === undefined) { return '—'; }
  if (seconds < 60) { return `${seconds}s`; }
  const pad = (n: number) => String(n).padStart(2, '0');
  if (seconds < 3600) { return `${Math.floor(seconds / 60)}m ${pad(seconds % 60)}s`; }
  return `${Math.floor(seconds / 3600)}h ${pad(Math.floor((seconds % 3600) / 60))}m`;
}

/** A holder's age as it matters to the change: its transaction's, else its time in state. */
export function holderAge(h: LockHolder): number | null {
  return h.transactionSeconds ?? h.secondsInState;
}

/** Old enough that the change would queue every query on the table behind it. */
export const OLD_SECONDS = 5;

/** The oldest holder on a server, or null when there are none. */
export function oldestHolder(server: LockHolderServer): LockHolder | null {
  let oldest: LockHolder | null = null;
  for (const h of server.holders) {
    if (oldest === null || (holderAge(h) ?? -1) > (holderAge(oldest) ?? -1)) { oldest = h; }
  }
  return oldest;
}
