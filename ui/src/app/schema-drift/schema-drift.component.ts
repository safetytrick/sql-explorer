import {Component, OnDestroy, OnInit} from '@angular/core';
import {RouterLink} from '@angular/router';
import {Subscription} from 'rxjs';
import {CoverageService, usedVariant} from '../coverage.service';
import {EnvironmentService} from '../environment.service';
import type {TableDrift} from '../api-types';

/**
 * Tables defined differently by clusters carrying the same schema — its own
 * page (sqlx-om1.6), where it was a card on Captured. Captured is about what
 * was recorded; drift is about the schema itself, and usually means a
 * migration applied to some shards and not others.
 *
 * The data is the coverage report's, so it is per environment and re-read on
 * a switch, like Captured.
 */
@Component({
  selector: 'app-schema-drift',
  templateUrl: './schema-drift.component.html',
  styleUrls: ['./schema-drift.component.scss'],
  imports: [RouterLink]
})
export class SchemaDriftComponent implements OnInit, OnDestroy {
  drift: TableDrift[] = [];
  loading = true;
  failed = false;
  protected readonly usedVariant = usedVariant;
  private readonly subscriptions = new Subscription();

  constructor(private coverageService: CoverageService, private environmentService: EnvironmentService) {}

  ngOnInit() {
    this.subscriptions.add(this.environmentService.current$.subscribe(() => this.load()));
  }

  ngOnDestroy() {
    this.subscriptions.unsubscribe();
  }

  private load() {
    this.loading = true;
    this.failed = false;
    this.drift = [];
    this.coverageService.getCoverage().subscribe({
      next: report => { this.drift = report.drift ?? []; this.loading = false; },
      // an empty page would read as "no drift", which a failed request cannot say
      error: () => { this.failed = true; this.loading = false; }
    });
  }
}
