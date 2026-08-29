import {Component} from '@angular/core';
import {RouterLink} from '@angular/router';
import {Location} from '@angular/common';

/**
 * The `**` catch-all. Before this existed an unknown URL matched no route,
 * rendered an empty outlet, and left the address bar to reset itself to `/` —
 * which looks like the app is broken rather than like the link is wrong.
 *
 * It shows the URL that missed, because these are almost always *sent* links:
 * knowing what was asked for is what lets someone see the typo, or tell the
 * person who sent it what arrived. `Location.path()` is read once on
 * construction rather than from the router, since the router's own URL is
 * already the matched (catch-all) route by the time this renders.
 */
@Component({
  selector: 'app-not-found',
  templateUrl: './not-found.component.html',
  styleUrls: ['./not-found.component.scss'],
  imports: [RouterLink]
})
export class NotFoundComponent {

  readonly attempted: string;

  constructor(location: Location) {
    this.attempted = location.path() || '/';
  }
}
