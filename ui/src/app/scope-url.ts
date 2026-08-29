import {inject} from '@angular/core';
import {CanActivateFn, Params, Router} from '@angular/router';

/**
 * The query parameters that follow you from page to page.
 *
 * <p>Membership is not a taste call. Inventorying all 47 of the app's query
 * parameters (`docs/plans/url-parameter-vocabulary.md`) yielded one rule that
 * decides every one of them without a special case:
 *
 * <blockquote>A parameter travels if it names <b>objects in the corpus</b>. It
 * stays if it is a <b>predicate over the rows this page happens to
 * list</b>.</blockquote>
 *
 * <p>These six name schemas, tables, or tags attached to them, so the
 * statement they make is identical whatever page reads it. Everything else —
 * how a page draws itself, what it is being asked, what is selected inside it,
 * which of its own rows it keeps — is defined relative to one page and would
 * change meaning on arrival. `tags` and `tagMode` are the sharp case: they
 * filter queries on `/queries` and tables on `/tables`, with different
 * defaults, so carrying them would silently ask a different question.
 *
 * <p>Deliberately absent: the graph's `tag` and `source`. They name a corpus
 * set but act as <em>selectors</em> — they choose what the graph <b>is</b>,
 * not a stance held while you do something else.
 */
export const SCOPE_PARAMS = [
  'schemas',
  'tables',
  'tableMode',
  'schemaTags',
  'schemaTagsExclude',
  'schemaTagMode'
] as const;

/** The scope half of a parameter set, with empties dropped. */
export function scopeOf(params: Params): Params {
  const scope: Params = {};
  for (const name of SCOPE_PARAMS) {
    if (params[name] != null && params[name] !== '') {
      scope[name] = params[name];
    }
  }
  return scope;
}

/** Which scope parameters are set on the way out but absent on the way in. */
export function droppedScope(leaving: Params, arriving: Params): Params {
  const carried: Params = {};
  for (const [name, value] of Object.entries(scopeOf(leaving))) {
    if (arriving[name] == null) {
      carried[name] = value;
    }
  }
  return carried;
}

/**
 * Carries {@link SCOPE_PARAMS} across a navigation that would otherwise drop
 * them.
 *
 * <p>Angular's `routerLink` discards query parameters by default. The app has
 * 37 of them, so the alternative to this guard is annotating every one and
 * remembering to annotate the next — which fails the first time someone
 * forgets, silently, in a way nothing tests. One guard on the route table
 * catches every navigation instead: links, typed URLs, and back/forward alike.
 *
 * <p><b>Why this cannot resurrect a scope you deliberately cleared.</b>
 * Clearing happens on the page you are already on — a same-route change to the
 * query string. Angular's default `runGuardsAndResolvers` is `paramsChange`,
 * which explicitly "does not include query parameters", so the guard does not
 * re-run and has nothing to put back. It fires only on a real route change,
 * which is precisely when parameters get dropped. Setting
 * `runGuardsAndResolvers` to any of the `…QueryParamsChange` variants on a
 * route would break that, and the breakage would look like a filter that
 * refuses to switch off.
 *
 * <p>Returning a {@link UrlTree} restarts the navigation, so the guard runs a
 * second time — and terminates, because the parameters are present by then.
 * The target's own parameters win over the carried ones, so a link that names
 * a scope explicitly is never overridden by the one you were browsing under.
 */
export const carryScope: CanActivateFn = (route, state) => {
  const router = inject(Router);
  const carried = droppedScope(router.routerState.snapshot.root.queryParams, route.queryParams);
  if (!Object.keys(carried).length) {
    return true;
  }
  const target = router.parseUrl(state.url);
  target.queryParams = {...carried, ...target.queryParams};
  return target;
};
