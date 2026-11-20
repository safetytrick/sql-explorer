import { Injectable, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, catchError, combineLatest, of, shareReplay, startWith, tap } from 'rxjs';
import { map } from 'rxjs/operators';
import { EnvironmentService } from './environment.service';
import { Author } from './author';

const endpoint = '/api/sql-explorer/';

/** The login entry point: remembers `return`, then redirects to the provider (sqlx-e13.1). */
export const LOGIN_PATH = endpoint + 'login';

/** `oidc`: people sign in. `none`: sign-in is not configured, and everyone is the legacy admin. */
export type AuthMode = 'oidc' | 'none';

/** `GET /user`. */
export interface CurrentUser {
  mode: AuthMode;
  /** The row authorship is recorded against; `admin` when sign-in is off, null for a visitor. */
  user: (Author & { name?: string | null }) | null;
  issuer: string | null;
  subject: string | null;
  /** The provider's `groups` claim as sent. */
  groups: string[];
  /** What those groups grant, as `role:scope` (`viewer:prod`, `admin:*`); sqlx-e13.4. */
  grants: string[];
  /** Whether the operator endpoints are open to them. */
  admin: boolean;
  /**
   * Per environment they can see, the environment-level permissions held
   * there. An environment that is absent is one they cannot see at all.
   */
  environments: Record<string, EnvPermission[]>;
  /**
   * The access token the request was made with (sqlx-e13.5). Never set for
   * the UI, which signs in through a browser; an API client asking `/user`
   * sees which token it is using, and `grants` are that token's.
   */
  token?: {id: number; name: string} | null;
  /**
   * Not signed in, reading what `sql-explorer.auth.anonymous` lets visitors
   * read (sqlx-2lc). Sign in for more: anything beyond reading answers 401,
   * which the auth interceptor turns into a sign-in.
   */
  anonymous?: boolean;
}

/** The environment-level permissions the backend enforces (sqlx-e13.4). */
export type EnvPermission = 'explorer:read' | 'commentary:write' | 'env:explain' | 'env:sample' | 'env:pull';

/**
 * Whether `user` holds `permission` on environment `env`. Only for leaving
 * out controls the backend would refuse; the backend decides.
 */
export function can(user: CurrentUser | null | undefined, permission: EnvPermission, env: string | null | undefined): boolean {
  return !!user && !!env && (user.environments?.[env] ?? []).includes(permission);
}

/**
 * Leaving the app for a full-page navigation: sign-in and sign-out both go
 * through the provider, which an XHR cannot follow. A class of its own so
 * specs can stand in for the browser.
 */
@Injectable({ providedIn: 'root' })
export class BrowserNavigation {

  private leaving = false;

  /** Where the user is now, to come back to: path, query and fragment. */
  here(): string {
    return window.location.pathname + window.location.search + window.location.hash;
  }

  assign(url: string): void {
    window.location.assign(url);
  }

  /**
   * To the login entry point, coming back here afterwards. Once per page:
   * a page that fires several requests gets several 401s, and one
   * navigation is enough.
   */
  signIn(): void {
    if (this.leaving) {
      return;
    }
    this.leaving = true;
    this.assign(this.loginUrl());
  }

  /** The login entry point, coming back to where the user is now. */
  loginUrl(): string {
    return LOGIN_PATH + '?return=' + encodeURIComponent(this.here());
  }
}

@Injectable({
  providedIn: 'root'
})
export class UserService {

  private current$?: Observable<CurrentUser>;

  /** `/user`'s answer once someone has asked for it (the nav always does); null before. */
  private readonly known = signal<CurrentUser | null>(null);

  constructor(private http: HttpClient, private navigation: BrowserNavigation, private environments: EnvironmentService) { }

  /** Who is signed in. Asked once per page; a sign-in or sign-out reloads the page anyway. */
  getCurrentUser(): Observable<CurrentUser> {
    if (!this.current$) {
      this.current$ = this.http.get<CurrentUser>(endpoint + 'user').pipe(
        tap(user => this.known.set(user)),
        shareReplay(1));
    }
    return this.current$;
  }

  /**
   * Whether `permission` is known to be refused on the environment in the
   * address bar (sqlx-387): for leaving out controls, such as a tag's `+`, that
   * would only send a visitor to sign in and lose what they typed.
   *
   * <p>Deliberately passive and the opposite way round from {@link can}: it
   * never asks `/user` itself, and is false until the answer is in. A control
   * on every row of a list cannot afford a request each, nor to vanish for
   * everyone whenever `/user` is slow; the backend still refuses what it
   * refuses. A signal, so a template reading it updates when the answer lands.
   */
  denied(permission: EnvPermission): boolean {
    const user = this.known();
    const env = this.environments.urlName();
    return !!user && !!env && !can(user, permission, env);
  }

  /**
   * Whether the current user holds `permission` on the environment requests
   * are made against. False until `/user` answers, so a control appears
   * rather than flashing and vanishing.
   */
  can(permission: EnvPermission): Observable<boolean> {
    return combineLatest([this.getCurrentUser(), this.environments.effective()]).pipe(
      map(([user, env]) => can(user, permission, env)),
      startWith(false),
      catchError(() => of(false)));
  }

  /**
   * Ends the session, then follows the server to wherever it says next: the
   * provider's end-session endpoint, which signs out there too and sends the
   * browser back. The POST carries the XSRF token, which Angular's HttpClient
   * copies from the cookie.
   */
  signOut(): Observable<string> {
    return this.http.post<{ location?: string }>(endpoint + 'logout', null).pipe(
      map(response => {
        const location = response?.location || '/';
        this.navigation.assign(location);
        return location;
      }));
  }
}
