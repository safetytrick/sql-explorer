import type {ParamMap, Params} from '@angular/router';

/** Response types for `/topology` (sqlx-a9h); see `topology/Topology.java`. */
/**
 * What a statement does (QueryKind on the backend). UNKNOWN is a statement
 * whose operation could not be read, usually because its text was truncated,
 * and that changed no rows.
 */
export type StatementKind = 'READ' | 'WRITE' | 'OTHER' | 'UNKNOWN';
export type ServerStatus = 'READ' | 'STALE' | 'UNREACHABLE' | 'NO_SOURCE' | 'NOT_LOADED';

export interface Share {
  kind: StatementKind;
  statements: number;
  executions: number;
  seconds: number;
}

export interface Load {
  statements: number;
  executions: number;
  seconds: number;
  /** Total seconds over the server's own window, floored at a day; a cluster's is its servers' added. */
  secondsPerDay: number | null;
  /** READ, WRITE, OTHER, UNKNOWN — always all four, in that order. */
  shares: Share[];
}

export interface TopologyWindow {
  from: string;
  to: string;
  days: number;
}

export interface TopologyServer {
  name: string;
  roles: string[];
  schemaSource: boolean;
  status: ServerStatus;
  load: Load | null;
  window: TopologyWindow | null;
  shareOfTime: number | null;
  reason: string | null;
}

export interface TopologyCluster {
  name: string;
  servers: TopologyServer[];
  load: Load;
}

export interface TopologyReport {
  environment: string;
  loaded: boolean;
  cached: boolean;
  clusters: TopologyCluster[];
  others: TopologyServer[];
  total: Load;
  basis: string;
}

export interface TopologyStatement {
  digest: string;
  type: string;
  kind: StatementKind;
  schema: string;
  query: string;
  executions: number;
  seconds: number;
  shareOfServer: number;
}

export interface ServerStatements {
  environment: string;
  server: string;
  status: ServerStatus;
  load: Load;
  statements: TopologyStatement[];
}

/** What the split and the node size are measured by. */
export type Measure = 'time' | 'executions';

/** Page state, all of it in the URL: `var-server`, `var-by` (time is the default and is omitted). */
export interface TopologyState {
  server: string | null;
  by: Measure;
}

export function decodeTopologyParams(params: ParamMap): TopologyState {
  return {
    server: params.get('var-server') || null,
    by: params.get('var-by') === 'executions' ? 'executions' : 'time'
  };
}

/** Null clears a param, so an untouched view keeps a clean URL. */
export function encodeTopologyParams(state: TopologyState): Params {
  return {
    'var-server': state.server || null,
    'var-by': state.by === 'executions' ? 'executions' : null
  };
}

export const KIND_LABELS: Record<StatementKind, string> =
  {READ: 'reads', WRITE: 'writes', OTHER: 'other', UNKNOWN: 'unknown'};

/** A legend key worth showing: reads, writes and other always; unknown only when there is some. */
export function shownInLegend(segment: Segment): boolean {
  return segment.kind !== 'UNKNOWN' || segment.fraction > 0;
}

/** One segment of a split bar: its kind and its fraction of the whole, 0–1. */
export interface Segment {
  kind: StatementKind;
  fraction: number;
  value: number;
}

/** Reads, writes and other as fractions of the chosen measure; empty when there is nothing to split. */
export function split(load: Load | null | undefined, by: Measure): Segment[] {
  if (!load) {
    return [];
  }
  const value = (share: Share) => by === 'time' ? share.seconds : share.executions;
  const total = load.shares.reduce((sum, share) => sum + value(share), 0);
  if (total <= 0) {
    return [];
  }
  return load.shares.map(share => ({kind: share.kind, fraction: value(share) / total, value: value(share)}));
}

/** 0.4 → "40%"; under 1% but not zero → "<1%", so a sliver is not read as none. */
export function percent(fraction: number | null | undefined): string {
  if (fraction == null) {
    return '—';
  }
  if (fraction > 0 && fraction < 0.01) {
    return '<1%';
  }
  return Math.round(fraction * 100) + '%';
}

/** Query seconds as a reader wants them: 42 s, 3.2 h, 18 d. */
export function formatSeconds(seconds: number | null | undefined): string {
  if (seconds == null) {
    return '—';
  }
  if (seconds < 1) {
    return seconds === 0 ? '0 s' : (seconds * 1000).toFixed(0) + ' ms';
  }
  if (seconds < 120) {
    return seconds.toFixed(seconds < 10 ? 1 : 0) + ' s';
  }
  if (seconds < 7200) {
    return (seconds / 60).toFixed(0) + ' min';
  }
  if (seconds < 172800) {
    return (seconds / 3600).toFixed(1) + ' h';
  }
  return (seconds / 86400).toFixed(1) + ' d';
}

/** Every server in the report, clusters first, then sources naming no configured server. */
export function allServers(report: TopologyReport | null): TopologyServer[] {
  if (!report) {
    return [];
  }
  return [...report.clusters.flatMap(cluster => cluster.servers), ...report.others];
}

/**
 * A cluster drawn as a primary over what replicates from it. Servers with no
 * primary role (a single-server cluster, or config that declares no roles)
 * are all drawn on one row.
 */
export function arrange(cluster: TopologyCluster): {top: TopologyServer[]; below: TopologyServer[]} {
  const top = cluster.servers.filter(server => server.roles.includes('primary'));
  if (!top.length) {
    return {top: cluster.servers, below: []};
  }
  return {top, below: cluster.servers.filter(server => !server.roles.includes('primary'))};
}

/** Why a server shows no figures, in a few words for its node. */
export function statusLabel(status: ServerStatus): string {
  switch (status) {
    case 'READ': return '';
    case 'STALE': return 'previous capture';
    case 'UNREACHABLE': return 'not read';
    case 'NO_SOURCE': return 'not captured';
    case 'NOT_LOADED': return 'not loaded';
  }
}
