import {Component, DestroyRef, inject} from '@angular/core';
import {DatePipe} from '@angular/common';
import {FormsModule} from '@angular/forms';
import {ActivatedRoute, Router, RouterLink} from '@angular/router';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {AccessTokenService} from './access-token.service';
import {
  AccessTokenList, AccessTokenView, CreatedToken, lifetimeChoices, optionsByScope, readTokensView, refusal, roleOf,
  tokensViewParams, TokensView
} from './access-tokens';

/**
 * Personal access tokens (sqlx-e13.5): for MCP and API clients, which cannot
 * sign in through a browser. Each acts as you, but never beyond the ceiling
 * chosen here, and never beyond your own grants as of your latest sign-in.
 *
 * <p>The view is in the URL as the rest of the app's is: `var-all` for an
 * administrator's listing of everyone's, `var-new` while the form is open.
 * What is typed into the form is not, and the secret shown after making a
 * token is deliberately kept out of it: a secret in an address ends up in
 * history, and is shown once.
 */
@Component({
  selector: 'app-access-tokens',
  templateUrl: './access-tokens.component.html',
  styleUrls: ['./access-tokens.component.scss'],
  imports: [DatePipe, FormsModule, RouterLink]
})
export class AccessTokensComponent {

  private readonly tokens = inject(AccessTokenService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  view: TokensView = {all: false, creating: false};
  list: AccessTokenList | null = null;
  loadError: string | null = null;

  name = '';
  chosen = new Set<string>();
  lifetimeDays = 0;
  creatingError: string | null = null;
  saving = false;

  /** The token just made, with its secret: on this page until it is left, and nowhere else. */
  created: CreatedToken | null = null;
  copied = false;

  /** A token whose Revoke was clicked once, waiting for the second click. */
  confirming: number | null = null;
  revokeError: string | null = null;

  readonly optionsByScope = optionsByScope;
  readonly roleOf = roleOf;

  constructor() {
    this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(params => {
      const next = readTokensView(params);
      const reload = this.list === null || next.all !== this.view.all;
      this.view = next;
      if (reload) {
        this.load();
      }
    });
  }

  get lifetimes(): number[] {
    return lifetimeChoices(this.list?.maxLifetimeDays ?? 90);
  }

  get canCreate(): boolean {
    return !this.saving && this.name.trim().length > 0 && this.chosen.size > 0;
  }

  load() {
    this.loadError = null;
    this.tokens.list(this.view.all).subscribe({
      next: list => {
        this.list = list;
        if (!this.lifetimeDays || this.lifetimeDays > list.maxLifetimeDays) {
          this.lifetimeDays = list.maxLifetimeDays;
        }
        // a choice no longer offered (a group lost since) is not kept
        this.chosen = new Set([...this.chosen].filter(grant => list.ceilingOptions.includes(grant)));
      },
      error: error => { this.loadError = refusal(error); }
    });
  }

  setView(change: Partial<TokensView>) {
    this.router.navigate([], {
      queryParams: tokensViewParams({...this.view, ...change}),
      queryParamsHandling: 'merge',
      replaceUrl: true
    });
  }

  toggle(grant: string, on: boolean) {
    const next = new Set(this.chosen);
    if (on) { next.add(grant); } else { next.delete(grant); }
    this.chosen = next;
  }

  create() {
    if (!this.canCreate || !this.list) {
      return;
    }
    this.saving = true;
    this.creatingError = null;
    this.tokens.create({name: this.name.trim(), ceiling: [...this.chosen], expiresInDays: this.lifetimeDays}).subscribe({
      next: created => {
        this.saving = false;
        this.created = created;
        this.copied = false;
        this.name = '';
        this.chosen = new Set();
        this.setView({creating: false});
        this.load();
      },
      error: error => {
        this.saving = false;
        this.creatingError = refusal(error);
      }
    });
  }

  copySecret() {
    if (!this.created) {
      return;
    }
    navigator.clipboard?.writeText(this.created.secret).then(() => { this.copied = true; });
  }

  dismissSecret() {
    this.created = null;
  }

  revoke(token: AccessTokenView) {
    if (this.confirming !== token.id) {
      this.confirming = token.id;
      return;
    }
    this.confirming = null;
    this.revokeError = null;
    this.tokens.revoke(token.id).subscribe({
      next: () => this.load(),
      error: error => { this.revokeError = refusal(error); }
    });
  }

  ownerOf(token: AccessTokenView): string {
    return token.owner?.name || token.owner?.email || (token.owner ? `user ${token.owner.id}` : '');
  }
}
