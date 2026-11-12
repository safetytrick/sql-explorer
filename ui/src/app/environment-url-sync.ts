import {Injectable, inject} from '@angular/core';
import {Location} from '@angular/common';
import {NavigationEnd, Router} from '@angular/router';
import {filter, merge} from 'rxjs';
import {EnvironmentService} from './environment.service';

/**
 * Keeps the environment at the front of the address bar (`/prod/queries`,
 * sqlx-e13.7) so every view can be sent as a link.
 *
 * <p>The router's serializer ({@link EnvPathUrlSerializer}) already writes it
 * into every URL the app makes. What is left for this is the address the app
 * was OPENED at, which no serializer wrote: a bare `/queries`, or an old
 * `/queries?env=prod` link. Both are rewritten in place once the environment
 * is known, which for a bare path means once the backend has said what its
 * default is.
 *
 * <p>WRITTEN ALWAYS, EVEN WHEN IT IS THE DEFAULT. A bare URL is precisely the
 * ambiguous case: if the common environment stayed unwritten, the links most
 * likely to be shared would be the ones that say least.
 *
 * <p>`replaceState` rather than a navigation: the page is already right, only
 * the address is not, and the back button should leave the page rather than
 * step through rewrites.
 */
@Injectable({providedIn: 'root'})
export class EnvironmentUrlSync {

  private readonly router = inject(Router);
  private readonly location = inject(Location);
  private readonly environments = inject(EnvironmentService);
  private started = false;

  /** Called once, from the root component. */
  start(): void {
    if (this.started) {
      return;
    }
    this.started = true;
    merge(
      this.router.events.pipe(filter(event => event instanceof NavigationEnd)),
      this.environments.known$,
    ).subscribe(() => this.stamp());
    this.stamp();
  }

  /**
   * Select an environment, by loading the same view against it.
   *
   * <p>A FULL DOCUMENT LOAD, deliberately: the address describes the whole
   * view, so re-requesting it reconstructs exactly what the user was looking
   * at, against the new corpus, with no component able to keep a stale row.
   * (Switching used to bounce the router through `/`, which on the landing
   * page never left the route, so the data did not reload.)
   */
  switchTo(name: string): void {
    // Persisted first: a full load re-reads this, and the URL, on the way up.
    this.environments.setCurrent(name);
    location.assign(this.location.prepareExternalUrl(this.router.url));
  }

  /** Rewrite the address to the router's own form of it, if they differ. */
  stamp(): void {
    // Not before the router's first navigation has finished: until then its
    // URL is the initial `/`, not the page being opened (sqlx-dkq).
    if (!this.router.navigated) {
      return;
    }
    const want = this.router.url;
    if (this.location.path(true) !== want) {
      this.location.replaceState(want);
    }
  }
}
