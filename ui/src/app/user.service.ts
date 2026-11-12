import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, shareReplay } from 'rxjs';
import { map } from 'rxjs/operators';
import { Author } from './author';

const endpoint = '/api/sql-explorer/';

/** The login entry point: remembers `return`, then redirects to the provider (sqlx-e13.1). */
export const LOGIN_PATH = endpoint + 'login';

/** `oidc`: people sign in. `none`: sign-in is not configured, and everyone is the legacy admin. */
export type AuthMode = 'oidc' | 'none';

/** `GET /user`. */
export interface CurrentUser {
  mode: AuthMode;
  /** The row authorship is recorded against; `admin` when sign-in is off. */
  user: Author & { name?: string | null };
  issuer: string | null;
  subject: string | null;
  /** The provider's `groups` claim as sent. Reported only; roles are sqlx-e13.4. */
  groups: string[];
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
    this.assign(LOGIN_PATH + '?return=' + encodeURIComponent(this.here()));
  }
}

@Injectable({
  providedIn: 'root'
})
export class UserService {

  private current$?: Observable<CurrentUser>;

  constructor(private http: HttpClient, private navigation: BrowserNavigation) { }

  /** Who is signed in. Asked once per page; a sign-in or sign-out reloads the page anyway. */
  getCurrentUser(): Observable<CurrentUser> {
    if (!this.current$) {
      this.current$ = this.http.get<CurrentUser>(endpoint + 'user').pipe(shareReplay(1));
    }
    return this.current$;
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
