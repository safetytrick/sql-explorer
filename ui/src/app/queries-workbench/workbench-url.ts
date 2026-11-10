/**
 * The workbench's own presentation params, beside the shared search
 * vocabulary in search-url.ts.
 *
 * Search params are spoken identically by every page that links into a
 * search, so view state that only the workbench has — which rows show their
 * whole SQL — does not belong there. It follows the graph's
 * convention instead (graph-url.ts): page-owned params carry a `var-` prefix
 * so they can never collide with the cross-cutting ones.
 */

/** Minimal read surface of Angular's ParamMap, so specs need no router. */
export interface ParamReader { get(name: string): string | null; }

export interface WorkbenchViewState {
  /**
   * The rows that are the exception to `fullSql`, by digest — expanded rows
   * when it is off, collapsed rows when it is on. Digests are the identity
   * `/analyzed-sql/:id` links use, so a shared link opens the same rows; and
   * keeping only the exceptions keeps "all of them but two" a short link.
   */
  expanded: string[];
  /** Every row shows its whole formatted SQL (sqlx-ywy.1). */
  fullSql: boolean;
  /** The detail pane under the list; off by default (sqlx-ywy.5). */
  detailPane: boolean;
  /**
   * The side panels folded away, so the results — list or graph — get the
   * page: `tree` (the schema tree) and `filters`. `var-hide=tree,filters`.
   */
  hide: WorkbenchPanel[];
}

export type WorkbenchPanel = 'tree' | 'filters';
export const WORKBENCH_PANELS: readonly WorkbenchPanel[] = ['tree', 'filters'];

const EXPANDED = 'var-expanded';
const SQL = 'var-sql';
const DETAIL = 'var-detail';
const HIDE = 'var-hide';

/** The workbench-owned params; defaults are omitted, so an untouched view keeps a clean URL. */
export function encodeWorkbenchParams(state: WorkbenchViewState): Record<string, string> {
  const params: Record<string, string> = {};
  if (state.expanded.length) {
    params[EXPANDED] = state.expanded.join(',');
  }
  if (state.fullSql) {
    params[SQL] = 'full';
  }
  if (state.detailPane) {
    params[DETAIL] = '1';
  }
  // in a fixed order, so the same panels always make the same link
  const hide = WORKBENCH_PANELS.filter(panel => state.hide.includes(panel));
  if (hide.length) {
    params[HIDE] = hide.join(',');
  }
  return params;
}

export function decodeWorkbenchParams(params: ParamReader): WorkbenchViewState {
  const expanded = (params.get(EXPANDED) ?? '').split(',').map(id => id.trim()).filter(id => id.length);
  const hide = (params.get(HIDE) ?? '').split(',').map(panel => panel.trim());
  return {
    expanded: [...new Set(expanded)],
    fullSql: params.get(SQL) === 'full',
    detailPane: params.get(DETAIL) === '1',
    hide: WORKBENCH_PANELS.filter(panel => hide.includes(panel))
  };
}
