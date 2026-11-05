import {inject, Injectable} from '@angular/core';
import {Title} from '@angular/platform-browser';
import {ActivatedRouteSnapshot, ResolveFn, RouterStateSnapshot, TitleStrategy} from '@angular/router';

const APP_NAME = 'SQL Explorer';

/** One spelling for every tab: "what you are looking at · SQL Explorer". */
export function formatPageTitle(page: string | null | undefined): string {
  return page ? `${page} · ${APP_NAME}` : APP_NAME;
}

/**
 * Titles come from each route's `title`, so a page cannot forget to set one
 * and keep the previous page's (sqlx-m6r.1). Routes used to carry
 * `data: { title }`, which nothing read; half the pages set a title by hand
 * and the rest silently kept the last one.
 *
 * A page whose title depends on data it fetches (the query page) still calls
 * {@link Title} itself, through {@link formatPageTitle}, once the data is in.
 */
@Injectable()
export class PageTitleStrategy extends TitleStrategy {
  private readonly title = inject(Title);

  override updateTitle(snapshot: RouterStateSnapshot): void {
    this.title.setTitle(formatPageTitle(this.buildTitle(snapshot)));
  }
}

/** A title built from route params, e.g. `paramTitle(p => p['schema'])`. */
export function paramTitle(build: (params: Record<string, string>) => string): ResolveFn<string> {
  return (route: ActivatedRouteSnapshot) => build(route.params as Record<string, string>);
}
