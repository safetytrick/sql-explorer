import {Component, DestroyRef, inject} from '@angular/core';
import {FormsModule} from '@angular/forms';
import {HttpClient} from '@angular/common/http';
import {ActivatedRoute, Router} from '@angular/router';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {decodePreflightParams, encodePreflightParams, Preflight, PREFLIGHT_ENDPOINT} from './ddl-preflight';
import {PreflightResultComponent} from './preflight-result.component';

const EXAMPLE = 'ALTER TABLE app_main.account\n  ADD COLUMN name_u8 VARCHAR(255) CHARACTER SET utf8mb4 NULL,\n'
  + '  ALGORITHM=INSTANT';

/**
 * Advice on a schema change before it runs (Admin → DDL pre-flight). The
 * statement and threshold live in the link, so a check can be shared; nothing
 * is ever executed, only judged against the schema snapshot and captured
 * statistics.
 */
@Component({
  selector: 'app-ddl-preflight',
  templateUrl: './ddl-preflight.component.html',
  styleUrls: ['./ddl-preflight.component.scss'],
  imports: [FormsModule, PreflightResultComponent]
})
export class DdlPreflightComponent {
  readonly example = EXAMPLE;

  ddl = '';
  longSeconds = 1;
  result: Preflight | null = null;
  busy = false;
  failed = false;

  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);

  constructor() {
    const state = decodePreflightParams(this.route.snapshot.queryParamMap);
    this.ddl = state.ddl;
    this.longSeconds = state.longSeconds;
    if (this.ddl.trim()) { this.check(false); }
  }

  check(writeUrl = true) {
    if (!this.ddl.trim() || this.busy) { return; }
    if (writeUrl) {
      this.router.navigate([], {
        relativeTo: this.route, replaceUrl: true,
        queryParams: {...this.carried(), ...encodePreflightParams({ddl: this.ddl, longSeconds: this.longSeconds})}
      });
    }
    this.busy = true;
    this.failed = false;
    this.http.post<Preflight>(PREFLIGHT_ENDPOINT, {sql: this.ddl, longStatementSeconds: this.longSeconds})
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: result => {
          this.result = result;
          this.busy = false;
        },
        error: () => {
          this.result = null;
          this.failed = true;
          this.busy = false;
        }
      });
  }

  useExample() {
    this.ddl = EXAMPLE;
  }

  /** Params this page does not own (the environment, for one) travel with it. */
  private carried(): Record<string, string> {
    const params = this.route.snapshot.queryParamMap;
    const out: Record<string, string> = {};
    for (const key of params.keys) {
      if (key !== 'var-ddl' && key !== 'var-long') { out[key] = params.get(key)!; }
    }
    return out;
  }
}
