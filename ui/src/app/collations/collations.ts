import {Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {Observable} from 'rxjs';
import {matchesTreeFilter} from '../schema-tree/tree-filter';

const endpoint = '/api/sql-explorer/';

// ── the `/collations` payload (CollationReportService, sqlx-n5j.5) ──

export interface EncodingCount {
  characterSet: string | null;
  collation: string | null;
  columns: string[];
}

export interface ColumnEncoding {
  column: string;
  characterSet: string | null;
  collation: string | null;
}

/** One character column and what it presumably holds — inferred from name and type, never from data. */
export interface TextColumn {
  column: string;
  /** VARCHAR(255), ENUM, TEXT … */
  type: string;
  characterSet: string | null;
  collation: string | null;
  kind: 'identifier' | 'enum' | 'text';
  /** The rule that produced the kind. */
  basis: string;
  /** Not on the table's baseline encoding. */
  differs: boolean;
}

export interface TableEncoding {
  schema: string;
  table: string;
  view: boolean;
  /** Columns carrying a charset or collation; 0 for a table of numbers and dates. */
  textColumns: number;
  /** The most-used encoding among the table's character columns. */
  predominantCharset: string | null;
  predominantCollation: string | null;
  /** The table's declared default (TABLE_COLLATION) when every server reporting it agrees; else null. */
  declaredCharset?: string | null;
  declaredCollation?: string | null;
  /** What the outliers were compared with. */
  baseline?: 'declared' | 'predominant';
  mixedCharsets: boolean;
  mixedCollations: boolean;
  encodings: EncodingCount[];
  /** Columns not on the baseline encoding: the declared default when known, else the predominant one. */
  outliers: ColumnEncoding[];
  /** Every character column, in table order. */
  columns?: TextColumn[];
}

export interface JoinSide {
  schema: string;
  table: string;
  column: string;
  characterSet: string | null;
  collation: string | null;
}

export interface JoinEncodingMismatch {
  left: JoinSide;
  right: JoinSide;
  charsetDiffers: boolean;
  queries: number;
  executions: number;
  exampleDigest: string | null;
}

export interface CollationReport {
  tables: TableEncoding[];
  joins: JoinEncodingMismatch[];
}

@Injectable({ providedIn: 'root' })
export class CollationsService {
  constructor(private http: HttpClient) { }

  getReport(): Observable<CollationReport> {
    return this.http.get<CollationReport>(endpoint + 'collations');
  }
}

// ── the page's URL state ──

/**
 * The page's own filters, each `var-` prefixed: they are predicates over the
 * rows this page lists, not names of corpus objects, so they must not collide
 * with — or travel like — the cross-cutting scope parameters. All default to
 * off and are omitted when off.
 */
export const NAME_PARAM = 'var-name';
export const CHARSET_PARAM = 'var-charset';
export const COLLATION_PARAM = 'var-collation';
export const FLAG_PARAM = 'var-flag';
/** The tables expanded to show their columns, as `schema.table`, comma-separated. */
export const OPEN_PARAM = 'var-open';

export function tableKey(table: {schema: string; table: string}): string {
  return `${table.schema}.${table.table}`;
}

export function decodeOpenTables(params: ParamReader): Set<string> {
  const raw = params.get(OPEN_PARAM);
  return new Set(raw ? raw.split(',').map(value => value.trim()).filter(Boolean) : []);
}

export function encodeOpenTables(open: Set<string>): Record<string, string | null> {
  return {[OPEN_PARAM]: open.size ? [...open].sort().join(',') : null};
}

export interface EncodingFilter {
  /** Matched against `schema.table` as the tree filter matches: text, `*` wildcards, or `/regex/`. */
  name: string;
  charset: string | null;
  collation: string | null;
  /** Only tables that mix charsets or collations. */
  mixedOnly: boolean;
}

export const NO_ENCODING_FILTER: EncodingFilter = {name: '', charset: null, collation: null, mixedOnly: false};

export interface ParamReader {
  get(name: string): string | null;
}

export function decodeEncodingFilter(params: ParamReader): EncodingFilter {
  return {
    name: params.get(NAME_PARAM) ?? '',
    charset: params.get(CHARSET_PARAM) || null,
    collation: params.get(COLLATION_PARAM) || null,
    // anything but the one value it can take is the default, not an empty page
    mixedOnly: params.get(FLAG_PARAM) === 'mixed'
  };
}

export function encodeEncodingFilter(filter: EncodingFilter): Record<string, string | null> {
  return {
    [NAME_PARAM]: filter.name.trim() ? filter.name : null,
    [CHARSET_PARAM]: filter.charset,
    [COLLATION_PARAM]: filter.collation,
    [FLAG_PARAM]: filter.mixedOnly ? 'mixed' : null
  };
}

// ── grouping and filtering, pure so they can be specified without a page ──

/** Tables that use exactly the same set of encodings. */
export interface EncodingGroup {
  key: string;
  encodings: {characterSet: string | null; collation: string | null}[];
  /** More than one charset or collation within each table of the group. */
  mixed: boolean;
  tables: TableEncoding[];
}

function same(a: string | null, b: string | null): boolean {
  return (a ?? '').toLowerCase() === (b ?? '').toLowerCase();
}

export function isMixed(table: TableEncoding): boolean {
  return table.mixedCharsets || table.mixedCollations;
}

/**
 * Whether a table passes the page's filters. Charset and collation match any
 * encoding the table uses, not only its predominant one — "which tables touch
 * utf8mb3 at all" is the question a conversion plan asks.
 */
export function matchesEncodingFilter(table: TableEncoding, filter: EncodingFilter): boolean {
  if (!matchesTreeFilter(table.schema, table.table, filter.name)) { return false; }
  if (filter.mixedOnly && !isMixed(table)) { return false; }
  if (filter.charset && !table.encodings.some(e => same(e.characterSet, filter.charset))) { return false; }
  if (filter.collation && !table.encodings.some(e => same(e.collation, filter.collation))) { return false; }
  return true;
}

/** A join passes when either of its sides would. */
export function joinMatchesFilter(join: JoinEncodingMismatch, filter: EncodingFilter): boolean {
  const side = (s: JoinSide) => matchesTreeFilter(s.schema, s.table, filter.name)
    && (!filter.charset || same(s.characterSet, filter.charset))
    && (!filter.collation || same(s.collation, filter.collation));
  return side(join.left) || side(join.right);
}

/**
 * Tables with character columns, grouped by the exact set of encodings they
 * use, largest group first. A table using two collations lands in a group of
 * its own kind ("a + b"), not in both, so each table is listed once and the
 * mixed groups stand apart.
 */
export function groupByEncoding(tables: TableEncoding[]): EncodingGroup[] {
  const groups = new Map<string, EncodingGroup>();
  for (const table of tables) {
    if (!table.textColumns) { continue; }
    const encodings = [...table.encodings]
      .map(e => ({characterSet: e.characterSet, collation: e.collation}))
      .sort((a, b) => `${a.characterSet}/${a.collation}`.localeCompare(`${b.characterSet}/${b.collation}`));
    const key = encodings.map(e => `${e.characterSet ?? '?'}/${e.collation ?? '?'}`).join(' + ').toLowerCase();
    let group = groups.get(key);
    if (!group) {
      group = {key, encodings, mixed: isMixed(table), tables: []};
      groups.set(key, group);
    }
    group.tables.push(table);
  }
  return [...groups.values()].sort((a, b) => b.tables.length - a.tables.length || a.key.localeCompare(b.key));
}

/** Every distinct charset and collation in the tables, sorted, for the filter choices. */
export function encodingChoices(tables: TableEncoding[]): {charsets: string[]; collations: string[]} {
  const charsets = new Set<string>();
  const collations = new Set<string>();
  for (const table of tables) {
    for (const encoding of table.encodings) {
      if (encoding.characterSet) { charsets.add(encoding.characterSet); }
      if (encoding.collation) { collations.add(encoding.collation); }
    }
  }
  return {charsets: [...charsets].sort(), collations: [...collations].sort()};
}
