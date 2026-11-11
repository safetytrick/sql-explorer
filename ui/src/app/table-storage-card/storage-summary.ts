import {TableStorageStats} from '../api-types';
import {ServerRole} from '../environment.service';

/**
 * One table's storage across the servers that report it, reduced to a figure
 * each. Pure, so the arithmetic is specified without a page.
 *
 * <p>ONLY PRIMARIES ARE ADDED UP. Each cluster (shard) holds its own rows, so
 * shards sum; a replica holds a copy of its primary's rows, so adding it would
 * count them twice. A server counts unless config marks it a replica and not a
 * primary — a server with no roles counts, because roles are optional and most
 * environments declare none.
 */
export interface StorageSummary {
  /** The entries the totals were taken over. */
  counted: TableStorageStats[];
  /** Entries left out of the totals: replicas. */
  excluded: TableStorageStats[];
  rows: number | null;
  data: number | null;
  indexes: number | null;
  free: number | null;
  /** Data bytes over rows across the counted servers: an overall average, not a mean of averages. */
  avgRow: number | null;
  /** The highest next AUTO_INCREMENT value. */
  nextId: number | null;
  /** The earliest creation time. */
  created: string | null;
  /** The latest update time. */
  updated: string | null;
  /** The oldest read: the figures are at least this stale. */
  readOldest: string | null;
  readNewest: string | null;
  /** One value when every counted server agrees; otherwise each distinct value. */
  engines: string[];
  rowFormats: string[];
  collations: string[];
}

function isReplicaOnly(roles: ServerRole[] | undefined): boolean {
  return !!roles && roles.includes('replica') && !roles.includes('primary');
}

function sum(values: (number | null | undefined)[]): number | null {
  const present = values.filter((value): value is number => value !== null && value !== undefined);
  return present.length ? present.reduce((total, value) => total + value, 0) : null;
}

function distinct(values: (string | null | undefined)[]): string[] {
  const seen = new Map<string, string>();
  for (const value of values) {
    if (value) { seen.set(value.toLowerCase(), seen.get(value.toLowerCase()) ?? value); }
  }
  return [...seen.values()].sort();
}

function extreme(values: (string | null | undefined)[], latest: boolean): string | null {
  const present = values.filter((value): value is string => !!value)
    .map(value => ({value, time: Date.parse(value)}))
    .filter(entry => !Number.isNaN(entry.time))
    .sort((a, b) => a.time - b.time);
  if (!present.length) { return null; }
  return (latest ? present[present.length - 1] : present[0]).value;
}

export function summarizeStorage(stats: TableStorageStats[] | null | undefined,
                                 roles: Record<string, ServerRole[] | undefined> = {}): StorageSummary {
  const all = stats ?? [];
  let counted = all.filter(entry => !isReplicaOnly(roles[entry.server]));
  // every reporter a replica: better a figure from a copy than none
  if (!counted.length) { counted = all; }
  const excluded = all.filter(entry => !counted.includes(entry));

  const rows = sum(counted.map(entry => entry.rowsEstimate));
  const data = sum(counted.map(entry => entry.dataLength));
  const autoIncrements = counted.map(entry => entry.autoIncrement)
    .filter((value): value is number => value !== null && value !== undefined);

  return {
    counted,
    excluded,
    rows,
    data,
    indexes: sum(counted.map(entry => entry.indexLength)),
    free: sum(counted.map(entry => entry.dataFree)),
    avgRow: rows && data !== null ? Math.round(data / rows) : null,
    nextId: autoIncrements.length ? Math.max(...autoIncrements) : null,
    created: extreme(counted.map(entry => entry.createTime), false),
    updated: extreme(counted.map(entry => entry.updateTime), true),
    readOldest: extreme(counted.map(entry => entry.readAt), false),
    readNewest: extreme(counted.map(entry => entry.readAt), true),
    engines: distinct(counted.map(entry => entry.engine)),
    rowFormats: distinct(counted.map(entry => entry.rowFormat)),
    collations: distinct(counted.map(entry => entry.tableCollation))
  };
}

/** "InnoDB", or "mixed: InnoDB, MyISAM"; null when nothing was reported. */
export function oneValue(values: string[]): string | null {
  if (!values.length) { return null; }
  return values.length === 1 ? values[0] : `mixed: ${values.join(', ')}`;
}
