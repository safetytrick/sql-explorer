/**
 * URL state for the graph, which is a view of the workbench:
 * `/queries?view=graph` (sqlx-aoy.16). What the graph shows is the workbench
 * search, in the shared vocabulary of search-url.ts; this file covers only
 * how it is drawn.
 *
 * Convention: params the graph *owns* carry a `var-` prefix, grafana-style,
 * so they can never collide with the search params beside them.
 *
 * Back-compat: the graph used to be its own page, `/graph` and
 * `/graph/:schema`, scoped by `?tag=` or `?source=search`. Those links
 * redirect here through {@link graphRedirectParams}, and the old `?table=`
 * highlight param is still read.
 */

export interface GraphLayers {
  fk: boolean;
  /** Tables joined to each other in a query, from analysis (sqlx-aoy.3). */
  join: boolean;
  /** Queried together but never joined: every joined pair is left to `join`. */
  co: boolean;
  self: boolean;
  view: boolean;
}

export type GraphLayout = 'force' | 'grid';

/** The workbench param that shows the graph in place of the list. */
export const VIEW_PARAM = 'view';
export const GRAPH_VIEW = 'graph';

/** The params the graph owns, which the workbench keeps while the graph shows. */
export const GRAPH_PARAMS = ['var-table', 'var-layers', 'var-layout', 'var-tables', 'var-legend'] as const;

export interface GraphUrlState {
  /** Selected table (name only — matches node labels), null for none. */
  table: string | null;
  layers: GraphLayers;
  /** Absent means grid. */
  layout?: GraphLayout;
  /**
   * Every table of the scoped schemas, queried or not, with the foreign keys
   * between them, rather than only the tables the matching queries touch.
   * `var-tables=all`. Does nothing unless the search is scoped to schemas.
   */
  allTables?: boolean;
}

/**
 * Joins (and views) alone: what the queries actually do. Foreign keys,
 * co-queried pairs and self-joins are a click away but off, because on a
 * schema of any size they bury the joins.
 */
export const DEFAULT_LAYERS: GraphLayers = { fk: false, join: true, co: false, self: false, view: true };

export const DEFAULT_LAYOUT: GraphLayout = 'grid';

const LAYER_NAMES = ['fk', 'join', 'co', 'self', 'view'] as const;

/** `var-layers` value meaning "every layer off" (an empty param would be dropped). */
const NO_LAYERS = 'none';

/** Minimal read surface of Angular's ParamMap, so specs need no router. */
export interface ParamReader { get(name: string): string | null; }

function sameLayers(a: GraphLayers, b: GraphLayers): boolean {
  return LAYER_NAMES.every(name => a[name] === b[name]);
}

/**
 * The graph-owned params, null-cleared at defaults — for
 * `queryParamsHandling: 'merge'` writes, so selection and layer changes never
 * disturb the search params beside them.
 */
export function graphOwnedParamPatch(state: GraphUrlState): Record<string, string | null> {
  const enabled = LAYER_NAMES.filter(name => state.layers[name]);
  return {
    'var-table': state.table || null,
    'var-layers': sameLayers(state.layers, DEFAULT_LAYERS) ? null : (enabled.length ? enabled.join(',') : NO_LAYERS),
    'var-layout': (state.layout ?? DEFAULT_LAYOUT) === 'force' ? 'force' : null,
    'var-tables': state.allTables ? 'all' : null
  };
}

export function decodeGraphParams(params: ParamReader): GraphUrlState {
  return {
    table: params.get('var-table') ?? params.get('table'),
    layers: decodeLayers(params.get('var-layers')),
    layout: params.get('var-layout') === 'force' ? 'force' : 'grid',
    allTables: params.get('var-tables') === 'all'
  };
}

/**
 * Where an old `/graph` link goes: the workbench, showing the graph, asking
 * the same question.
 *
 * <ul>
 *   <li>`/graph/:schema` graphed the whole schema, so it becomes a search
 *       scoped to that schema with every table drawn (`var-tables=all`).</li>
 *   <li>`?tag=` graphed the queries carrying a tag: the search `tags`.</li>
 *   <li>`?source=search` already carried the search; the marker is dropped.</li>
 * </ul>
 * Everything else (the search, the scope, the graph's own `var-` params, the
 * environment) is kept as it is.
 */
export function graphRedirectParams(schema: string | null, params: Record<string, unknown>): Record<string, unknown> {
  const result: Record<string, unknown> = {};
  for (const [name, value] of Object.entries(params)) {
    if (name !== 'source' && name !== 'tag' && name !== 'table') { result[name] = value; }
  }
  if (params['table'] && !params['var-table']) { result['var-table'] = params['table']; }
  if (params['tag']) { result['tags'] = params['tag']; }
  if (schema) {
    const schemas = String(params['schemas'] ?? '').split(',').filter(Boolean);
    result['schemas'] = [...new Set([...schemas, schema])].join(',');
    result['var-tables'] = 'all';
  }
  result[VIEW_PARAM] = GRAPH_VIEW;
  return result;
}

function decodeLayers(raw: string | null): GraphLayers {
  if (raw === null) {
    return { ...DEFAULT_LAYERS };
  }
  const enabled = new Set(raw === NO_LAYERS ? [] : raw.split(',').filter(Boolean));
  return {
    fk: enabled.has('fk'),
    join: enabled.has('join'),
    co: enabled.has('co'),
    self: enabled.has('self'),
    view: enabled.has('view')
  };
}
