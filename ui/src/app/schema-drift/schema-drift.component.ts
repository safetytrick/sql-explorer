import {Component, OnDestroy, OnInit} from '@angular/core';
import {ActivatedRoute, Router, RouterLink} from '@angular/router';
import {Subscription} from 'rxjs';
import {CoverageService, usedVariant} from '../coverage.service';
import {EnvironmentService} from '../environment.service';
import {decodeOpenTables, encodeOpenTables} from '../collations/collations';
import type {DriftVariant, TableDrift} from '../api-types';

/**
 * Tables defined differently by clusters carrying the same schema — its own
 * page (sqlx-om1.6), where it was a card on Captured. Captured is about what
 * was recorded; drift is about the schema itself, and usually means a
 * migration applied to some shards and not others.
 *
 * The data is the coverage report's, so it is per environment and re-read on
 * a switch, like Captured. A row opens to show how each variant differs from
 * the one lookups use (sqlx-a6z); open rows are in the URL as `var-open`.
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
  /** Tables opened to show their differences, by `schema.table`. */
  open = new Set<string>();
  protected readonly usedVariant = usedVariant;
  private readonly subscriptions = new Subscription();

  constructor(private coverageService: CoverageService, private environmentService: EnvironmentService,
              private route: ActivatedRoute, private router: Router) {}

  ngOnInit() {
    this.subscriptions.add(this.route.queryParamMap.subscribe(params => this.open = decodeOpenTables(params)));
    this.subscriptions.add(this.environmentService.current$.subscribe(() => this.load()));
  }

  ngOnDestroy() {
    this.subscriptions.unsubscribe();
  }

  toggle(entry: TableDrift) {
    const open = new Set(this.open);
    if (open.has(entry.table)) { open.delete(entry.table); } else { open.add(entry.table); }
    this.open = open;
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: encodeOpenTables(open),
      queryParamsHandling: 'merge',
      replaceUrl: true
    });
  }

  /** The variants compared against the used one: every other variant, absent ones included. */
  others(entry: TableDrift): DriftVariant[] {
    const used = usedVariant(entry);
    return entry.variants.filter(variant => variant !== used);
  }

  /** `schema.table` split for the table link. */
  tableLink(entry: TableDrift): string[] {
    const dot = entry.table.indexOf('.');
    return ['/table', entry.table.slice(0, dot), entry.table.slice(dot + 1)];
  }

  /**
   * What kind of drift, at a glance: "absent on 2 clusters", "3 columns, 1 index",
   * or — when the structures could not be compared — nothing.
   */
  summary(entry: TableDrift): string {
    const parts: string[] = [];
    const absent = entry.variants.filter(variant => !variant.present).flatMap(variant => variant.clusters);
    if (absent.length) {
      parts.push(`absent on ${absent.length} cluster${absent.length === 1 ? '' : 's'}`);
    }
    const byKind = new Map<string, Set<string>>();
    for (const variant of entry.variants) {
      for (const difference of variant.differences ?? []) {
        const names = byKind.get(difference.kind) ?? new Set<string>();
        names.add(difference.name.toLowerCase());
        byKind.set(difference.kind, names);
      }
    }
    for (const [kind, names] of byKind) {
      const plural = kind === 'index' ? 'indexes' : `${kind}s`;
      parts.push(kind === 'table' ? 'table comment' : `${names.size} ${names.size === 1 ? kind : plural}`);
    }
    const unexplained = entry.variants.some(variant => variant.differences?.length === 0);
    if (!byKind.size && unexplained) {
      parts.push('a detail not compared');
    }
    return parts.join(', ');
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
