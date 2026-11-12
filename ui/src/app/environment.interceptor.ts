import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { switchMap } from 'rxjs';
import { EnvironmentService } from './environment.service';

const API = '/api/sql-explorer/';

/**
 * API paths that are not about one environment, matched against what follows
 * `/api/sql-explorer/`. Mirrors the controllers the backend leaves without
 * `@EnvironmentScoped`; its ApplicationContextTest pins that list.
 */
const GLOBAL = [
  /^environments(\/|$)/, /^user$/, /^util\//, /^analysis-failures(\/|$)/, /^graph\/layout(\/|$)/, /^mcp$/,
  /^login$/, /^logout$/, /^tokens(\/|$)/,
];

/**
 * Puts the environment into the path of every environment-scoped API request:
 * `/api/sql-explorer/schemas` goes out as `/api/sql-explorer/env/{env}/schemas`
 * (sqlx-e13.6). The environment used to travel as a header; in the path it is
 * something authorization can read, and an unknown name is a 404 instead of
 * the default environment's data.
 *
 * <p>Services keep writing unprefixed paths, so they did not have to change.
 */
export const environmentInterceptor: HttpInterceptorFn = (req, next) => {
  const rest = apiRest(req.url);
  if (rest === null || rest.startsWith('env/') || GLOBAL.some(g => g.test(rest.split('?')[0]))) {
    return next(req);
  }
  return inject(EnvironmentService).effective().pipe(
    switchMap(env => next(req.clone({url: withEnvironment(req.url, env)}))));
};

/** What follows `/api/sql-explorer/` in a URL, or null for any other URL. */
export function apiRest(url: string): string | null {
  const at = url.indexOf(API);
  return at < 0 ? null : url.slice(at + API.length);
}

/** The same API URL, under `/env/{env}/`. */
export function withEnvironment(url: string, env: string): string {
  const at = url.indexOf(API) + API.length;
  return url.slice(0, at) + 'env/' + encodeURIComponent(env) + '/' + url.slice(at);
}
