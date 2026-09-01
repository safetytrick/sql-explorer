import {InjectionToken} from '@angular/core';
import type {ParamMap} from '@angular/router';

/**
 * Which corpus a URL is about.
 *
 * <p>WHY THIS IS NOT `var-` PREFIXED. That prefix marks presentation a single
 * page owns, so it cannot collide with cross-cutting parameters. The
 * environment is the most cross-cutting value in the application — every page
 * resolves against it — so it belongs in the bare namespace with the scope
 * family, not among the page-owned ones (sqlx-29f).
 *
 * <p>WHY IT IS NOT IN THE PARAMETER INVENTORY. `docs/plans/url-parameter-
 * vocabulary.md` derived the rule that a parameter travels if it names objects
 * in the corpus, and stays if it is a predicate over the rows a page lists.
 * This is neither: it names WHICH CORPUS. That category did not exist when the
 * inventory was written, which is why the environment lived only in
 * localStorage — not an oversight of the rule but a gap it was never pointed at.
 */
export const ENV_PARAM = 'env';

/**
 * The environment a link names, or null when it names none.
 *
 * <p>A blank value is null rather than the empty string: `?env=` is a URL
 * someone trimmed, not a request for an environment called "".
 */
export function envFromParams(params: ParamMap): string | null {
  return clean(params.get(ENV_PARAM));
}

/**
 * The environment named by a raw query string.
 *
 * <p>Read synchronously at service construction, before the router has
 * resolved anything. That timing is the point: the HTTP interceptor stamps
 * `X-Sql-Explorer-Environment` from the selected environment on every request,
 * and the first of those can be issued by a component's constructor — earlier
 * than any router subscription would fire. Seeding from the router instead
 * would let a shared link's first requests go out against the recipient's
 * stored environment rather than the one the link names.
 */
export function envFromSearch(search: string): string | null {
  try {
    return clean(new URLSearchParams(search).get(ENV_PARAM));
  } catch {
    // a malformed query string is not a reason to fail to start
    return null;
  }
}

/**
 * The same address, naming this environment.
 *
 * <p>Used when switching: the nav bar re-instantiates the current route so its
 * data reloads for the new corpus, and the address it navigates back to has to
 * be the new one. Stamping afterwards instead loses the race — the URL to
 * return to is captured before the stamp lands.
 */
export function withEnv(url: string, name: string): string {
  const [path, search = ''] = url.split('?');
  const params = new URLSearchParams(search);
  params.set(ENV_PARAM, name);
  return path + '?' + params.toString();
}

function clean(value: string | null): string | null {
  const trimmed = value?.trim();
  return trimmed ? trimmed : null;
}

/**
 * The query string the application was opened with.
 *
 * <p>Injected rather than read from `location` directly so the startup
 * precedence — URL over storage over default — can be tested. `location.search`
 * is not configurable in Chrome, so a service that reads it inline has a
 * startup rule that can only be verified by hand.
 */
export const INITIAL_SEARCH = new InjectionToken<string>('initial query string', {
  providedIn: 'root',
  factory: () => typeof location === 'undefined' ? '' : location.search
});
