/**
 * The cross-page search URL vocabulary: everything a query search is made
 * of — the workbench's filter params *plus* the schema-tree scope
 * (schemas, tables, table mode, schema-tag filter) — encoded to/from
 * query params as pure functions.
 *
 * Both `/queries` and `/graph?source=search` speak this vocabulary with
 * identical param names, so the two URLs differ only in path and a shared
 * link reproduces the full scope for the recipient. Page-local
 * *presentation* params (the graph's `var-table`/`var-layers`) are the
 * only ones outside it — see graph-lens/graph-url.ts.
 *
 * The schema-tag filter is shared as *intent* (the tags, not the schemas
 * they resolved to at share time) and re-resolved against the live tag
 * map when the link opens — the same way date-math expressions like
 * `now-7d` stay live.
 */
import {decodeScopeTags, encodeScopeTags} from './scope-tags';
import type {Activity, Flag, QuerySearchRequest, SortKey, TableMode, TagMode} from './query-search.service';
import type {QueryScope, SchemaTagFilter, SchemaTagScope} from './query-scope.service';
import type {ColumnRef} from './refs';
import {resolveDateExpr} from './date-range-selector/date-math';

export interface RangeState { min: number | null; max: number | null; }

export interface SearchUrlState {
  queryRegex: string;
  activity: Activity;
  types: string[];
  firstAfter: string | null;
  firstBefore: string | null;
  lastAfter: string | null;
  lastBefore: string | null;
  tags: string[];
  tagMode: TagMode;
  untaggedOnly: boolean;
  avgTime: RangeState;
  maxTime: RangeState;
  avgRows: RangeState;
  selectivityPct: RangeState;
  rowsSorted: RangeState;
  fullScan: Flag;
  tmpTable: Flag;
  tmpDisk: Flag;
  errors: Flag;
  warnings: Flag;
  truncated: Flag;
  multiSchema: Flag;
  analyzerWarnings: boolean;
  /** Only queries that have at least one stored example — the ones that can be planned. */
  hasExamples: boolean;
  analysisFailed: boolean;
  cleanAnalysis: boolean;
  analysisSkipped: boolean;
  failureKind: 'ANALYZER' | 'PARSE' | null;
  sort: SortKey;
  /** Schema-tree selections: schemas, "schema.table" keys, ANY/ALL. */
  scope: QueryScope;
  /** Schema-tree tag filter — shared as intent, resolved at load. */
  schemaTagFilter: SchemaTagFilter;
  /**
   * `schema.table.column` references; a query kept if any of them *constrains*
   * it (join or criteria — not a bare select). The table lens's column
   * drill-down writes this, and because it is part of this vocabulary the same
   * link opens in the workbench unchanged.
   */
  columns: ColumnRef[];
}

/** Minimal read surface of Angular's ParamMap, so specs need no router. */
export interface ParamReader { get(name: string): string | null; has(name: string): boolean; }

export function defaultSearchState(): SearchUrlState {
  return {
    queryRegex: '',
    activity: 'ANY',
    types: [],
    firstAfter: null, firstBefore: null, lastAfter: null, lastBefore: null,
    tags: [], tagMode: 'ANY', untaggedOnly: false,
    avgTime: { min: null, max: null },
    maxTime: { min: null, max: null },
    avgRows: { min: null, max: null },
    selectivityPct: { min: null, max: null },
    rowsSorted: { min: null, max: null },
    fullScan: 'ANY', tmpTable: 'ANY', tmpDisk: 'ANY', errors: 'ANY',
    warnings: 'ANY', truncated: 'ANY', multiSchema: 'ANY',
    hasExamples: false,
    analyzerWarnings: false, analysisFailed: false, cleanAnalysis: false, analysisSkipped: false,
    failureKind: null,
    sort: 'IMPACT',
    scope: { schemas: [], tables: [], tableMode: 'ANY' },
    schemaTagFilter: { includeTags: [], excludeTags: [], tagMode: 'ANY' },
    columns: []
  };
}

/** Defaults are omitted, so an untouched search keeps a clean URL. */
export function encodeSearchParams(state: SearchUrlState): Record<string, string> {
  const params: Record<string, string> = {};
  const put = (key: string, value: string | null) => { if (value) { params[key] = value; } };
  const putRange = (key: string, r: RangeState) => {
    if (r.min !== null) { params[key + 'Min'] = String(r.min); }
    if (r.max !== null) { params[key + 'Max'] = String(r.max); }
  };
  put('q', state.queryRegex.trim() || null);
  if (state.activity !== 'ANY') { params['activity'] = state.activity; }
  if (state.types.length) { params['queryTypes'] = state.types.join(','); }
  put('firstAfter', state.firstAfter);
  put('firstBefore', state.firstBefore);
  put('lastAfter', state.lastAfter);
  put('lastBefore', state.lastBefore);
  if (state.tags.length) { params['tags'] = state.tags.join(','); }
  if (state.tagMode !== 'ANY') { params['tagMode'] = state.tagMode; }
  if (state.untaggedOnly) { params['untagged'] = '1'; }
  putRange('avgTime', state.avgTime);
  putRange('maxTime', state.maxTime);
  putRange('avgRows', state.avgRows);
  putRange('sel', state.selectivityPct);
  putRange('sorted', state.rowsSorted);
  for (const key of ['fullScan', 'tmpTable', 'tmpDisk', 'errors', 'warnings', 'truncated', 'multiSchema'] as const) {
    if (state[key] !== 'ANY') { params[key] = state[key]; }
  }
  if (state.analyzerWarnings) { params['analWarn'] = '1'; }
  if (state.hasExamples) { params['hasExamples'] = '1'; }
  if (state.analysisFailed) { params['analFail'] = '1'; }
  if (state.cleanAnalysis) { params['analClean'] = '1'; }
  if (state.analysisSkipped) { params['analSkipped'] = '1'; }
  if (state.failureKind) { params['failKind'] = state.failureKind; }
  if (state.sort !== 'IMPACT') { params['sort'] = state.sort; }
  // schema-tree scope
  if (state.scope.schemas.length) { params['schemas'] = state.scope.schemas.join(','); }
  if (state.scope.tables.length) { params['tables'] = state.scope.tables.join(','); }
  if (state.scope.tableMode !== 'ANY') { params['tableMode'] = state.scope.tableMode; }
  // the scope vocabulary is spelled in exactly one place — see scope-tags.ts
  for (const [key, value] of Object.entries(encodeScopeTags(state.schemaTagFilter))) {
    if (value !== null) { params[key] = value; }
  }
  if (state.columns.length) { params['columns'] = state.columns.join(','); }
  return params;
}

export function decodeSearchParams(params: ParamReader): SearchUrlState {
  const readRange = (key: string): RangeState => ({
    min: params.has(key + 'Min') ? Number(params.get(key + 'Min')) : null,
    max: params.has(key + 'Max') ? Number(params.get(key + 'Max')) : null
  });
  const csv = (key: string): string[] => params.get(key)?.split(',').filter(Boolean) || [];
  return {
    queryRegex: params.get('q') || '',
    activity: (params.get('activity') as Activity) || 'ANY',
    types: csv('queryTypes'),
    firstAfter: params.get('firstAfter'),
    firstBefore: params.get('firstBefore'),
    lastAfter: params.get('lastAfter'),
    lastBefore: params.get('lastBefore'),
    tags: csv('tags'),
    tagMode: (params.get('tagMode') as TagMode) || 'ANY',
    untaggedOnly: params.get('untagged') === '1',
    avgTime: readRange('avgTime'),
    maxTime: readRange('maxTime'),
    avgRows: readRange('avgRows'),
    selectivityPct: readRange('sel'),
    rowsSorted: readRange('sorted'),
    fullScan: (params.get('fullScan') as Flag) || 'ANY',
    tmpTable: (params.get('tmpTable') as Flag) || 'ANY',
    tmpDisk: (params.get('tmpDisk') as Flag) || 'ANY',
    errors: (params.get('errors') as Flag) || 'ANY',
    warnings: (params.get('warnings') as Flag) || 'ANY',
    truncated: (params.get('truncated') as Flag) || 'ANY',
    multiSchema: (params.get('multiSchema') as Flag) || 'ANY',
    analyzerWarnings: params.get('analWarn') === '1',
    hasExamples: params.get('hasExamples') === '1',
    analysisFailed: params.get('analFail') === '1',
    cleanAnalysis: params.get('analClean') === '1',
    analysisSkipped: params.get('analSkipped') === '1',
    failureKind: (params.get('failKind') as 'ANALYZER' | 'PARSE') || null,
    sort: (params.get('sort') as SortKey) || 'IMPACT',
    scope: {
      schemas: csv('schemas'),
      tables: csv('tables'),
      tableMode: (params.get('tableMode') as TableMode) || 'ANY'
    },
    schemaTagFilter: decodeScopeTags(params),
    columns: csv('columns')
  };
}

/** No tag constraints: `includedSchemas: null` = unconstrained. */
export const UNCONSTRAINED_TAG_SCOPE: SchemaTagScope =
  { includedSchemas: null, excludedSchemas: [], excludedTables: [] };

/**
 * Resolve the tag filter to the name lists the backend filters on (the schema
 * tree's logic, shared so the graph resolves a link identically). Schema names
 * and map keys are lower-cased normalized names.
 *
 * A tag can sit on schemas, on tables, or on both, and the two resolve to
 * different request fields — `excludeSchemas` and `excludeTables`. Excluding a
 * tag means "drop everything it marks", so a tag on both contributes to both
 * lists. `tablesByTag` is tag → `schema.table` keys; omit it and only the
 * schema half resolves, which is all an *include* filter uses.
 */
export function resolveSchemaTagScope(schemaNames: string[],
                                      tagsBySchema: Map<string, string[]>,
                                      filter: SchemaTagFilter,
                                      tablesByTag?: Map<string, string[]>): SchemaTagScope {
  const excluded = filter.excludeTags.length
    ? schemaNames.filter(name => {
        const tags = tagsBySchema.get(name) ?? [];
        return filter.excludeTags.some(tag => tags.includes(tag));
      })
    : [];
  const excludedTables = tablesByTag
    ? [...new Set(filter.excludeTags.flatMap(tag => tablesByTag.get(tag) ?? []))]
    : [];
  let included: string[] | null = null;
  if (filter.includeTags.length) {
    included = schemaNames.filter(name => {
      if (excluded.includes(name)) {
        return false;
      }
      const tags = tagsBySchema.get(name) ?? [];
      return filter.tagMode === 'ALL'
        ? filter.includeTags.every(tag => tags.includes(tag))
        : filter.includeTags.some(tag => tags.includes(tag));
    });
  }
  return { includedSchemas: included, excludedSchemas: excluded, excludedTables };
}

/**
 * SELECT also covers CTEs.
 *
 * A query's type is its first token, assigned at import without parsing
 * (`SqlEvidenceInputService`), so `WITH … SELECT …` is typed `WITH` and falls
 * out of a SELECT filter. Classifying it properly would mean reading past the
 * CTE body to the statement it feeds, which normalized and frequently
 * truncated performance_schema text often does not contain — so the filter is
 * widened instead, accepting the occasional CTE-wrapped-DML false positive.
 *
 * This happens at request-build time, deliberately, *not* in the URL codec:
 * the URL keeps the type the user actually picked, so a shared link still
 * reads `SELECT` and decodes back to `SELECT`. Picking `WITH` on its own is
 * unaffected and still returns only CTEs.
 */
export function expandSelectTypes(types: string[]): string[] {
  const upper = types.map(type => type.toUpperCase());
  if (!upper.includes('SELECT') || upper.includes('WITH')) {
    return types;
  }
  return [...types, 'WITH'];
}

/**
 * Assemble the backend request from decoded state + the resolved tag
 * scope (the workbench's buildRequest, shared with the graph). Date
 * expressions resolve here so relative ranges stay live.
 */
export function buildSearchRequest(state: SearchUrlState,
                                   tagScope: SchemaTagScope,
                                   paging: { limit: number | null; offset: number | null }): QuerySearchRequest {
  return {
    queryRegex: state.queryRegex.trim() || null,
    avgTimeMs: range(state.avgTime),
    maxTimeMs: range(state.maxTime),
    avgRowsScanned: range(state.avgRows),
    selectivity: state.selectivityPct.min === null && state.selectivityPct.max === null ? null
      : { min: pctToFraction(state.selectivityPct.min), max: pctToFraction(state.selectivityPct.max) },
    rowsSorted: range(state.rowsSorted),
    activity: state.activity === 'ANY' ? null : state.activity,
    types: state.types.length ? expandSelectTypes(state.types) : null,
    firstSeen: dates(resolveDateExpr(state.firstAfter), resolveDateExpr(state.firstBefore, true)),
    lastSeen: dates(resolveDateExpr(state.lastAfter), resolveDateExpr(state.lastBefore, true)),
    tags: state.tags.length ? state.tags : null,
    tagMode: state.tagMode,
    untaggedOnly: state.untaggedOnly || null,
    schemas: resolveRequestSchemas(state.scope.schemas, tagScope.includedSchemas),
    excludeSchemas: tagScope.excludedSchemas.length ? tagScope.excludedSchemas : null,
    excludeTables: tagScope.excludedTables.length ? tagScope.excludedTables : null,
    tables: state.scope.tables.length ? state.scope.tables : null,
    tableMode: state.scope.tableMode,
    columns: state.columns.length ? state.columns : null,
    fullScan: flag(state.fullScan),
    tmpTable: flag(state.tmpTable),
    tmpDisk: flag(state.tmpDisk),
    errors: flag(state.errors),
    warnings: flag(state.warnings),
    truncated: flag(state.truncated),
    multiSchema: flag(state.multiSchema),
    hasExamples: state.hasExamples || null,
    analyzerWarnings: state.analyzerWarnings || null,
    analysisFailed: state.analysisFailed || null,
    cleanAnalysis: state.cleanAnalysis || null,
    analysisSkipped: state.analysisSkipped || null,
    failureKind: state.failureKind,
    sort: state.sort,
    limit: paging.limit,
    offset: paging.offset
  };
}

/**
 * Manual schema checkboxes ∩ schema-tag include list.
 * {@code null} = unconstrained; empty array = include tags active but no schemas match.
 */
function resolveRequestSchemas(selected: string[], tagIncluded: string[] | null): string[] | null {
  if (tagIncluded === null) {
    return selected.length ? selected : null;
  }
  if (!selected.length) {
    return tagIncluded;
  }
  const allowed = new Set(tagIncluded);
  return selected.filter(schema => allowed.has(schema));
}

function range(state: RangeState): { min: number | null, max: number | null } | null {
  return state.min === null && state.max === null ? null : { min: state.min, max: state.max };
}

function pctToFraction(value: number | null): number | null {
  return value === null ? null : value / 100;
}

function dates(after: string | null, before: string | null) {
  return !after && !before ? null : { after: after || null, before: before || null };
}

function flag(value: Flag): Flag | null {
  return value === 'ANY' ? null : value;
}
