import {ParamMap} from '@angular/router';

/**
 * The pure half of the Access tokens page (sqlx-e13.5): the API's shapes, the
 * page's URL vocabulary, and the choices it offers. Kept out of the component
 * so they can be checked without rendering.
 */

export const TOKENS_PATH = '/api/sql-explorer/tokens';

/** One token as listed. The secret is never in it; only {@link CreatedToken} carries that, once. */
export interface AccessTokenView {
  id: number;
  name: string;
  /** The secret's first characters, to tell tokens apart. */
  prefix: string;
  /** Grants in the groups syntax: `viewer:prod`, `runner:prod/app`, `admin:*`. */
  ceiling: string[];
  createdOn: string;
  expiresOn: string;
  lastUsedOn: string | null;
  revokedOn: string | null;
  status: 'active' | 'expired' | 'revoked';
  /** Only in an administrator's listing of everyone's. */
  owner: {id: number; email: string | null; name: string | null} | null;
}

/** `GET /tokens`. */
export interface AccessTokenList {
  /** False with sign-in off, where tokens have nothing to do. */
  enabled: boolean;
  tokens: AccessTokenView[];
  /** The caller's own grants as tokens see them: from their latest sign-in. */
  grants: string[];
  /** What a new token's ceiling can be chosen from. */
  ceilingOptions: string[];
  maxLifetimeDays: number;
  /** Whether the caller may list and revoke everyone's. */
  admin: boolean;
}

export interface CreateTokenRequest {
  name: string;
  ceiling: string[];
  expiresInDays: number;
}

/** `POST /tokens`: the only response that ever carries the secret. */
export interface CreatedToken {
  token: AccessTokenView;
  secret: string;
}

// ── the URL ────────────────────────────────────────────────────────────────

/** Everyone's tokens rather than your own (administrators). Page-owned, so `var-`. */
export const ALL_PARAM = 'var-all';
/** The new-token form is open. The MCP page links here with it set. */
export const NEW_PARAM = 'var-new';

export interface TokensView {
  all: boolean;
  creating: boolean;
}

export function readTokensView(params: ParamMap): TokensView {
  return {all: params.get(ALL_PARAM) === 'true', creating: params.get(NEW_PARAM) === 'true'};
}

/** Query params for a view, defaults left out so an untouched page keeps a clean URL. */
export function tokensViewParams(view: TokensView): Record<string, string | null> {
  return {[ALL_PARAM]: view.all ? 'true' : null, [NEW_PARAM]: view.creating ? 'true' : null};
}

// ── choices ───────────────────────────────────────────────────────────────

/** Lifetimes to offer, in days: a few round ones under the maximum, then the maximum. */
export function lifetimeChoices(maxDays: number): number[] {
  const max = Math.max(1, Math.floor(maxDays));
  return [...[1, 7, 30, 90, 365].filter(days => days < max), max];
}

/**
 * The ceiling options, grouped by where they apply, strongest role first:
 * `everywhere` for `role:*`, else the environment or datasource.
 */
export function optionsByScope(options: string[]): {scope: string; grants: string[]}[] {
  const groups = new Map<string, string[]>();
  for (const option of options) {
    const at = option.indexOf(':');
    const scope = at < 0 || option.slice(at + 1) === '*' ? 'everywhere' : option.slice(at + 1);
    groups.set(scope, [...(groups.get(scope) ?? []), option]);
  }
  return [...groups].map(([scope, grants]) => ({scope, grants}));
}

/** `viewer:prod` → `Viewer`, for a checkbox label under its scope. */
export function roleOf(grant: string): string {
  const role = grant.split(':')[0];
  return role.charAt(0).toUpperCase() + role.slice(1);
}

/** The reason a refused request gives, for showing as written. */
export function refusal(error: {error?: {message?: string} | string; message?: string; status?: number}): string {
  if (error?.error && typeof error.error === 'object' && error.error.message) {
    return error.error.message;
  }
  return error?.status ? `HTTP ${error.status}` : error?.message ?? 'no answer';
}
