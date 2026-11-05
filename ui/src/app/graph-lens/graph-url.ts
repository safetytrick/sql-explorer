/**
 * URL state for the graph page, mirroring the workbench's
 * readFiltersFromUrl/writeFiltersToUrl pattern as pure functions.
 *
 * Convention: params the graph *owns* (its own variables) carry a `var-`
 * prefix, grafana-style, so they can never collide with cross-cutting
 * params — scope selectors (`tag`, `source`) and future global ones stay
 * unprefixed. The graph is the first adopter of the convention; the
 * workbench's unprefixed filter params are widely bookmarked and are left
 * as-is until a back-compat migration is worth doing.
 *
 * Back-compat: the graph's old `?table=` highlight param is still read
 * (write side uses `var-table` only).
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

export type GraphSource = 'schema' | 'tag' | 'search';

/**
 * How tables are placed: the force simulation, or nested grids of schema,
 * aggregate and table (sqlx-aoy.13). The grid is the default; `var-layout=force`
 * asks for the simulation. Links from before the switch say `var-layout=grid`,
 * which still reads as the grid.
 */
export type GraphLayout = 'force' | 'grid';

export interface GraphUrlState {
  /** Selected table (name only — matches node labels), null for none. */
  table: string | null;
  layers: GraphLayers;
  /** Absent means grid. */
  layout?: GraphLayout;
  /** Scope: a tag name, … */
  tag: string | null;
  /** …or the workbench search snapshot ('search'); both null → schema mode. */
  source: 'search' | null;
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
 * The complete query-param set for the graph page. Defaults are omitted so
 * a fresh page keeps a clean URL; `router.navigate` with this object (no
 * merge) drops params that fall back to defaults.
 */
export function encodeGraphParams(state: GraphUrlState): Record<string, string> {
  const params: Record<string, string> = {};
  if (state.tag) {
    params['tag'] = state.tag;
  } else if (state.source === 'search') {
    params['source'] = 'search';
  }
  if (state.table) {
    params['var-table'] = state.table;
  }
  if (!sameLayers(state.layers, DEFAULT_LAYERS)) {
    const enabled = LAYER_NAMES.filter(name => state.layers[name]);
    params['var-layers'] = enabled.length ? enabled.join(',') : NO_LAYERS;
  }
  if (state.layout === 'force') {
    params['var-layout'] = 'force';
  }
  return params;
}

/**
 * Just the graph-owned (`var-`) params, null-cleared at defaults — for
 * `queryParamsHandling: 'merge'` writes, so selection/layer changes never
 * disturb the co-resident search params on `/graph?source=search&…`.
 */
export function graphOwnedParamPatch(table: string | null, layers: GraphLayers,
                                     layout: GraphLayout = DEFAULT_LAYOUT): Record<string, string | null> {
  const enabled = LAYER_NAMES.filter(name => layers[name]);
  return {
    'var-table': table || null,
    'var-layers': sameLayers(layers, DEFAULT_LAYERS) ? null : (enabled.length ? enabled.join(',') : NO_LAYERS),
    'var-layout': layout === 'force' ? 'force' : null
  };
}

export function decodeGraphParams(params: ParamReader): GraphUrlState {
  const tag = params.get('tag');
  const source = params.get('source') === 'search' ? 'search' as const : null;
  return {
    table: params.get('var-table') ?? params.get('table'),
    layers: decodeLayers(params.get('var-layers')),
    layout: params.get('var-layout') === 'force' ? 'force' : 'grid',
    tag: tag || null,
    source: tag ? null : source
  };
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
