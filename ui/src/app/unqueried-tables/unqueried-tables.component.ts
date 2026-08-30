import {Component, OnInit} from '@angular/core';
import {ActivatedRoute, Router, RouterLink} from '@angular/router';
import {Title} from '@angular/platform-browser';
import {SchemaLensService} from '../schema-lens.service';
import {SchemaUsage, totals, unqueriedBySchema} from './unqueried-tables';

/** Include MySQL's own schemas. Off by default; they are 295 of the 382. */
const SYSTEM_PARAM = 'var-system';

/**
 * Tables the schema describes that no analyzed query touches.
 *
 * <p>Grouped by schema rather than listed flat, because the grouping is the
 * finding. Measured on the regression corpus: of 87 unqueried tables outside
 * MySQL's own schemas, `app_iam` contributes 14 of its 14, `app_gdpr` 5 of 5 and
 * `app_temporal` 29 of 32 — those are schemas with no captured traffic, not
 * schemas full of dead tables. `del` contributing 5 of 30 is the actionable
 * case. A flat list would read as 87 deletion candidates and about half of
 * them would be wrong.
 */
@Component({
  selector: 'app-unqueried-tables',
  templateUrl: './unqueried-tables.component.html',
  styleUrls: ['./unqueried-tables.component.scss'],
  imports: [RouterLink]
})
export class UnqueriedTablesComponent implements OnInit {

  all: SchemaUsage[] = [];
  loading = true;
  failed = false;
  includeSystem = false;

  constructor(private schemaLensService: SchemaLensService,
              private route: ActivatedRoute,
              private router: Router,
              titleService: Title) {
    titleService.setTitle('::: unqueried tables :::');
  }

  ngOnInit() {
    this.route.queryParamMap.subscribe(params => {
      this.includeSystem = params.get(SYSTEM_PARAM) === '1';
    });
    this.schemaLensService.getSchemaTree().subscribe({
      next: tree => { this.all = unqueriedBySchema(tree); this.loading = false; },
      // an empty list would read as "nothing is unqueried", which is a strong
      // and probably wrong claim to make out of a failed request
      error: () => { this.failed = true; this.loading = false; }
    });
  }

  get rows(): SchemaUsage[] {
    return this.includeSystem ? this.all : this.all.filter(usage => !usage.system);
  }

  get counts() {
    return totals(this.rows);
  }

  get systemSchemaCount(): number {
    return this.all.filter(usage => usage.system).length;
  }

  setIncludeSystem(include: boolean) {
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: {[SYSTEM_PARAM]: include ? '1' : null},
      queryParamsHandling: 'merge',
      replaceUrl: true
    });
  }
}
