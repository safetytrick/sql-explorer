/**
 * The workbench's own presentation params, beside the shared search
 * vocabulary in search-url.ts.
 *
 * Search params are spoken identically by the workbench and by
 * `/graph?source=search`, so view state that only the workbench has — which
 * rows show their whole SQL — does not belong there. It follows the graph's
 * convention instead (graph-url.ts): page-owned params carry a `var-` prefix
 * so they can never collide with the cross-cutting ones.
 */

/** Minimal read surface of Angular's ParamMap, so specs need no router. */
export interface ParamReader { get(name: string): string | null; }

export interface WorkbenchViewState {
  /**
   * Rows showing their whole formatted SQL, by digest — the identity
   * `/analyzed-sql/:id` links use, so a shared link opens the same rows.
   */
  expanded: string[];
}

const EXPANDED = 'var-expanded';

/** The workbench-owned params; defaults are omitted, so an untouched view keeps a clean URL. */
export function encodeWorkbenchParams(state: WorkbenchViewState): Record<string, string> {
  const params: Record<string, string> = {};
  if (state.expanded.length) {
    params[EXPANDED] = state.expanded.join(',');
  }
  return params;
}

export function decodeWorkbenchParams(params: ParamReader): WorkbenchViewState {
  const expanded = (params.get(EXPANDED) ?? '').split(',').map(id => id.trim()).filter(id => id.length);
  return { expanded: [...new Set(expanded)] };
}
