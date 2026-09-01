import {Injectable, inject} from '@angular/core';
import {NavigationEnd, Router} from '@angular/router';
import {filter} from 'rxjs';
import {ENV_PARAM, envFromSearch} from './environment-url';
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
    // this, so the current URL is stamped directly rather than waited for.
    this.stamp();
  }

  /**
   * Put the selected environment on the URL, if it is not already right.
   *
   * <p>Public because picking an environment must correct the address
   * immediately, rather than waiting for whatever navigation happens next.
   */
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
    this.router.navigate([], {
      queryParams: {[ENV_PARAM]: current},
      queryParamsHandling: 'merge',
      replaceUrl: true
    });
  }
}
