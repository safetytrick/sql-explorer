/**
 * The criteria of a search as the pills the workbench shows above its
 * results, and the three things a pill does (sqlx-fph):
 *
 * - switch off and on again, keeping its value: `off=` in the link;
 * - flip between include and exclude, where negation means something;
 * - remove.
 *
 * Pure functions over {@link SearchUrlState}, which stays the one vocabulary
 * the workbench, the graph and every page linking into a search speak. Each
 * criterion has a stable key, the same whether it includes or excludes, so a
 * switched-off pill stays off when it is flipped.
 *
 * Only types are imported from search-url.ts: it imports
 * {@link effectiveState} from here at runtime.
 */
import type {RangeState, SearchUrlState} from './search-url';
import type {Flag} from './query-search.service';

export interface Criterion {
  /** Stable identity, the same included or excluded: `tag:legacy`, `fullScan`. What `off=` lists. */
  key: string;
  /** What it filters on, as the pill names it: "tag", "full scan". */
  label: string;
  /** The value, as shown. */
  value: string;
  exclude: boolean;
  /** Whether it can be flipped between include and exclude. */
  negatable: boolean;
  /** False when switched off: kept in the link, left out of the search. */
  on: boolean;
}

/** A criterion that is a value in an include list, an exclude list, or both. */
interface ListKind {
  kind: string;
  label: string;
  include?: (s: SearchUrlState) => string[];
  exclude?: (s: SearchUrlState) => string[];
}

const LISTS: ListKind[] = [
  { kind: 'schema', label: 'schema', include: s => s.scope.schemas, exclude: s => s.scope.excludeSchemas },
  { kind: 'table', label: 'table', include: s => s.scope.tables, exclude: s => s.scope.excludeTables },
  { kind: 'schemaTag', label: 'schema tag', include: s => s.schemaTagFilter.includeTags,
    exclude: s => s.schemaTagFilter.excludeTags },
  { kind: 'source', label: 'source', include: s => s.sources, exclude: s => s.excludeSources },
  { kind: 'type', label: 'type', include: s => s.types, exclude: s => s.excludeTypes },
  { kind: 'tag', label: 'tag', include: s => s.tags, exclude: s => s.excludeTags },
  { kind: 'tableTag', label: 'tables tagged', include: s => s.tableTags, exclude: s => s.excludeTableTags },
  { kind: 'column', label: 'column', include: s => s.columns },
  { kind: 'index', label: 'index', include: s => s.indexes },
  { kind: 'query', label: 'query', exclude: s => s.excludeQueries }
];

/** A criterion that is one setting: on when it differs from its default. */
interface ScalarKind {
  key: string;
  label: string;
  value: (s: SearchUrlState) => string | null;
  clear: (s: SearchUrlState) => void;
}

const range = (r: RangeState, unit: string): string | null => {
  if (r.min === null && r.max === null) { return null; }
  if (r.max === null) { return `≥ ${r.min}${unit}`; }
  if (r.min === null) { return `≤ ${r.max}${unit}`; }
  return `${r.min}–${r.max}${unit}`;
};
const dates = (after: string | null, before: string | null): string | null =>
  after || before ? `${after ?? '…'} → ${before ?? 'now'}` : null;
const flag = (value: Flag): string | null => value === 'ANY' ? null : value.toLowerCase();
const ANY_RANGE = (): RangeState => ({ min: null, max: null });

const SCALARS: ScalarKind[] = [
  { key: 'q', label: 'sql', value: s => s.queryRegex.trim() ? `/${s.queryRegex.trim()}/` : null,
    clear: s => { s.queryRegex = ''; } },
  { key: 'activity', label: 'activity', value: s => s.activity === 'ANY' ? null : s.activity.toLowerCase(),
    clear: s => { s.activity = 'ANY'; } },
  { key: 'firstSeen', label: 'first seen', value: s => dates(s.firstAfter, s.firstBefore),
    clear: s => { s.firstAfter = null; s.firstBefore = null; } },
  { key: 'lastSeen', label: 'last seen', value: s => dates(s.lastAfter, s.lastBefore),
    clear: s => { s.lastAfter = null; s.lastBefore = null; } },
  { key: 'untagged', label: 'untagged only', value: s => s.untaggedOnly ? 'yes' : null,
    clear: s => { s.untaggedOnly = false; } },
  { key: 'avgTime', label: 'avg time', value: s => range(s.avgTime, ' ms'), clear: s => { s.avgTime = ANY_RANGE(); } },
  { key: 'maxTime', label: 'max time', value: s => range(s.maxTime, ' ms'), clear: s => { s.maxTime = ANY_RANGE(); } },
  { key: 'avgRows', label: 'rows scanned', value: s => range(s.avgRows, ''), clear: s => { s.avgRows = ANY_RANGE(); } },
  { key: 'sel', label: 'selectivity', value: s => range(s.selectivityPct, '%'),
    clear: s => { s.selectivityPct = ANY_RANGE(); } },
  { key: 'sorted', label: 'rows sorted', value: s => range(s.rowsSorted, ''), clear: s => { s.rowsSorted = ANY_RANGE(); } },
  { key: 'fullScan', label: 'full scan', value: s => flag(s.fullScan), clear: s => { s.fullScan = 'ANY'; } },
  { key: 'tmpTable', label: 'tmp table', value: s => flag(s.tmpTable), clear: s => { s.tmpTable = 'ANY'; } },
  { key: 'tmpDisk', label: 'tmp on disk', value: s => flag(s.tmpDisk), clear: s => { s.tmpDisk = 'ANY'; } },
  { key: 'errors', label: 'errors', value: s => flag(s.errors), clear: s => { s.errors = 'ANY'; } },
  { key: 'warnings', label: 'warnings', value: s => flag(s.warnings), clear: s => { s.warnings = 'ANY'; } },
  { key: 'truncated', label: 'truncated', value: s => flag(s.truncated), clear: s => { s.truncated = 'ANY'; } },
  { key: 'multiSchema', label: 'multi-schema', value: s => flag(s.multiSchema), clear: s => { s.multiSchema = 'ANY'; } },
  { key: 'analWarn', label: 'analyzer warnings', value: s => s.analyzerWarnings ? 'yes' : null,
    clear: s => { s.analyzerWarnings = false; } },
  { key: 'hasExamples', label: 'has examples', value: s => s.hasExamples ? 'yes' : null,
    clear: s => { s.hasExamples = false; } },
  { key: 'analFail', label: 'analysis failed', value: s => s.analysisFailed ? 'yes' : null,
    clear: s => { s.analysisFailed = false; } },
  { key: 'analClean', label: 'clean analysis', value: s => s.cleanAnalysis ? 'yes' : null,
    clear: s => { s.cleanAnalysis = false; } },
  { key: 'analSkipped', label: 'analysis skipped', value: s => s.analysisSkipped ? 'yes' : null,
    clear: s => { s.analysisSkipped = false; } },
  { key: 'failKind', label: 'failure', value: s => s.failureKind?.toLowerCase() ?? null,
    clear: s => { s.failureKind = null; } },
  { key: 'hinted', label: 'index hint', value: s => s.hinted?.toLowerCase() ?? null, clear: s => { s.hinted = null; } },
  { key: 'generator', label: 'generator', value: s => s.generator, clear: s => { s.generator = null; } }
];

const listKey = (kind: string, value: string) => `${kind}:${value}`;

function scalarCriterion(scalar: ScalarKind, value: string, off: Set<string>): Criterion {
  return { key: scalar.key, label: scalar.label, value, exclude: false, negatable: false, on: !off.has(scalar.key) };
}

/** A deep copy to change: the state is plain data, so this is all a copy needs. */
function copy(state: SearchUrlState): SearchUrlState {
  return structuredClone(state);
}

function without(values: string[], value: string): void {
  const at = values.indexOf(value);
  if (at >= 0) { values.splice(at, 1); }
}

/** Every criterion of the search, in the order the filters panel lists them. */
export function criteriaOf(state: SearchUrlState): Criterion[] {
  const off = new Set(state.off);
  const result: Criterion[] = [];
  const pushList = (kind: ListKind, value: string, exclude: boolean) => {
    const key = listKey(kind.kind, value);
    result.push({ key, label: kind.label, value, exclude, negatable: !!(kind.include && kind.exclude), on: !off.has(key) });
  };
  // scope first, as the panel's tree is above everything else
  for (const kind of LISTS.slice(0, 3)) {
    kind.include?.(state).forEach(value => pushList(kind, value, false));
    kind.exclude?.(state).forEach(value => pushList(kind, value, true));
  }
  for (const scalar of SCALARS.slice(0, 4)) {
    const value = scalar.value(state);
    if (value !== null) { result.push(scalarCriterion(scalar, value, off)); }
  }
  for (const kind of LISTS.slice(3)) {
    kind.include?.(state).forEach(value => pushList(kind, value, false));
    kind.exclude?.(state).forEach(value => pushList(kind, value, true));
  }
  for (const scalar of SCALARS.slice(4)) {
    const value = scalar.value(state);
    if (value !== null) { result.push(scalarCriterion(scalar, value, off)); }
  }
  return result;
}

function listOf(key: string): { kind: ListKind; value: string } | null {
  const at = key.indexOf(':');
  if (at < 0) { return null; }
  const kind = LISTS.find(candidate => candidate.kind === key.slice(0, at));
  return kind ? { kind, value: key.slice(at + 1) } : null;
}

/** Switch a criterion off, keeping its value, or back on. */
export function toggleOff(state: SearchUrlState, key: string): SearchUrlState {
  const next = copy(state);
  if (next.off.includes(key)) { without(next.off, key); } else { next.off.push(key); }
  return next;
}

/** Move a list criterion between include and exclude. Its key, and so whether it is off, is unchanged. */
export function negate(state: SearchUrlState, key: string): SearchUrlState {
  const list = listOf(key);
  if (!list?.kind.include || !list.kind.exclude) { return state; }
  const next = copy(state);
  const include = list.kind.include(next);
  const exclude = list.kind.exclude(next);
  if (include.includes(list.value)) {
    without(include, list.value);
    exclude.push(list.value);
  } else if (exclude.includes(list.value)) {
    without(exclude, list.value);
    include.push(list.value);
  }
  return next;
}

/** Take a criterion out of the search altogether, and out of `off`. */
export function removeCriterion(state: SearchUrlState, key: string): SearchUrlState {
  const next = copy(state);
  clearInPlace(next, key);
  without(next.off, key);
  return next;
}

function clearInPlace(state: SearchUrlState, key: string): void {
  const list = listOf(key);
  if (list) {
    if (list.kind.include) { without(list.kind.include(state), list.value); }
    if (list.kind.exclude) { without(list.kind.exclude(state), list.value); }
    return;
  }
  SCALARS.find(scalar => scalar.key === key)?.clear(state);
}

/**
 * Add a value to a list criterion, included or excluded, moving it if it is
 * on the other side already and switching it back on if it was off. What the
 * sidebar's inputs, a query row's "exclude" and the graph's table detail use.
 */
export function addCriterion(state: SearchUrlState, kind: string, value: string, exclude: boolean): SearchUrlState {
  const list = LISTS.find(candidate => candidate.kind === kind);
  const target = list && (exclude ? list.exclude : list.include);
  if (!list || !target) { return state; }
  const next = copy(state);
  const key = listKey(kind, value);
  clearInPlace(next, key);
  target(next).push(value);
  without(next.off, key);
  return next;
}

/** The search as it runs: with the switched-off criteria taken out (sqlx-fph). */
export function effectiveState(state: SearchUrlState): SearchUrlState {
  if (!state.off.length) { return state; }
  const next = copy(state);
  for (const key of state.off) { clearInPlace(next, key); }
  next.off = [];
  return next;
}
