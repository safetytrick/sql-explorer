import {Injectable, inject} from '@angular/core';
import {NavigationEnd, Router} from '@angular/router';
import {filter} from 'rxjs';
import {ENV_PARAM, envFromSearch, withEnv} from './environment-url';
import {EnvironmentService} from './environment.service';

/**
 * Keeps `?env=` on the address bar so every view can be sent as a link.
 *
 * <p>WRITTEN ALWAYS, EVEN WHEN IT IS THE DEFAULT. This deliberately breaks the
 * omit-defaults rule, for the same reason the tag filter's `tagMode` breaks it:
 * a bare URL is precisely the ambiguous case. If the common environment were
 * the one that stayed unwritten, the links most likely to be shared would be
 * the ones that say least, and the parameter would buy nothing.
 *
 * <p>ONE SUBSCRIBER RATHER THAN A GUARD ON EVERY ROUTE. The vocabulary plan
 * raised route guards as the expensive, magical option for carrying the scope
 * family; the same objection is much weaker here, because this is a single
 * scalar with one writer and no page-local meaning. A `routerLink` never has to
 * remember it.
 *
 * <p>The re-navigation is guarded on the parameter already being correct, which
 * is what keeps it from looping: every navigation this performs satisfies the
 * condition that suppresses the next one. `replaceUrl` keeps it out of history,
 * so the back button leaves the page rather than stepping through stamps.
 */
@Injectable({providedIn: 'root'})
export class EnvironmentUrlSync {

  private readonly router = inject(Router);
  private readonly environments = inject(EnvironmentService);
  private started = false;


  /** Called once, from the root component. */
  start(): void {
    if (this.started) {
      return;
    }
    this.started = true;
    this.router.events
      .pipe(filter((event): event is NavigationEnd => event instanceof NavigationEnd))
      .subscribe(() => this.stamp());
    // The first NavigationEnd may already have fired by the time anything calls
    // this, so the current URL is stamped directly rather than waited for —
    // stamp() itself declines if that navigation is still under way.
    this.stamp();
  }

  /**
   * Select an environment, by loading the same view against it.
   *
   * <p>A FULL DOCUMENT LOAD, deliberately, and this is the piece that putting
   * `env` in the URL bought: the address already describes the whole view, so
   * re-requesting it reconstructs exactly what the user was looking at, against
   * the new corpus, with no component able to keep a stale row.
   *
   * <p>WHAT THIS REPLACES, AND WHY. Switching used to navigate to `/` with
   * `skipLocationChange` and back, to force Angular to re-instantiate the route
   * and re-fetch. Five of the ten routed pages subscribe to `current$` and
   * reload themselves; the rest depended entirely on that bounce. But `/`
   * redirects to `/tables`, so on the tables page — the landing page, and the
   * one most likely to be looked at — the bounce never left the route, Angular
   * reused the component, and the data simply did not reload. Verified against
   * the commit before `env` existed: it never worked there either.
   *
   * <p>An environment switch replaces every number on the page, so paying a
   * document load for it is the honest trade. It also cannot be subtly wrong,
   * which the bounce demonstrably could.
   */
  switchTo(name: string): void {
    // Persisted first: a full load re-reads this, and the URL, on the way up.
    this.environments.setCurrent(name);
    location.assign(withEnv(location.pathname + location.search, name));
  }

  /** Put the selected environment on the URL, if it is not already right. */
  stamp(): void {
    const current = this.environments.getCurrent();
    if (!current) {
      // Nothing selected means "whatever the backend defaults to". Writing a
      // name here would be inventing one the user never chose.
      return;
    }
    if (envFromSearch(location.search) === current) {
      return;
    }
    // Not before the router's first navigation has finished. Until then its
    // URL is the initial empty one, `/`, not the page being opened, and
    // navigate([]) keeps the router's URL — so stamping then navigated to `/`,
    // which redirects to /tables, and every link opened without ?env= (a
    // middle-click, a pasted address) landed there instead of on its page
    // (sqlx-dkq). The NavigationEnd that ends that navigation stamps it.
    if (!this.router.navigated) {
      return;
    }
    this.router.navigate([], {
      queryParams: {[ENV_PARAM]: current},
      queryParamsHandling: 'merge',
      replaceUrl: true
    });
  }
}
