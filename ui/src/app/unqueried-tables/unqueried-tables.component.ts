import {TagFieldComponent} from '../tag-field/tag-field.component';
import {tableTarget} from '../tag-path';
import {tableKey} from '../table-names/table-search-url';
import type {Tag} from '../api-types';
import {Component, OnDestroy, OnInit} from '@angular/core';
import {Subscription} from 'rxjs';
import {ActivatedRoute, Router, RouterLink} from '@angular/router';
import {DatePipe} from '@angular/common';
import {SchemaLensService} from '../schema-lens.service';
import {AnalyzedSqlService} from '../analyzed-sql.service';
import {CoverageService, findings, sharedWindow, worstVerdict} from '../coverage.service';
import {EnvironmentService} from '../environment.service';
import {SEEN_FORMAT} from '../seen-format';
import type {CoverageVerdict, ObservedWindow} from '../api-types';
import {SchemaUsage, totals, unqueriedBySchema} from './unqueried-tables';
import {matchesScopeTags, NO_SCOPE_TAGS, ScopeTags, decodeScopeTags, isUnscoped} from '../scope-tags';
import {schemaTarget} from '../tag-path';

/**
 * Also show the schemas the scope excluded. Off by default.
 *
 * <p>Replaces `var-system`, and the rename is the point: this used to reveal a
 * compiled-in list of MySQL's own schema names, which did by name what a
 * `system` tag does by tag — two mechanisms for one intent, and the hardcoded
 * one could not be adjusted per deployment or shared as a link. What is out of
 * scope now comes from the scope; this only decides whether you can see past it
 * (sqlx-g7z).
 *
 * <p>`var-` because it is presentation this page owns, which is what keeps it
 * from colliding with the cross-cutting scope parameters it sits beside.
 */
const EXCLUDED_PARAM = 'var-excluded';

/**
 * Tables the schema describes that no analyzed query touches.
 *
 * <p>Grouped by schema rather than listed flat, because the grouping is the
 * finding. On a real corpus most unqueried tables outside MySQL's own
 * schemas came from a few schemas that were unqueried in their entirety —
 * every table, or nearly — which are schemas with no captured traffic, not
 * schemas full of dead tables. A schema where a handful of its tables are
 * unqueried is the actionable case. A flat list would read as one long list of
 * deletion candidates and about half of them would be wrong.
 */
@Component({
  selector: 'app-unqueried-tables',
  templateUrl: './unqueried-tables.component.html',
  styleUrls: ['./unqueried-tables.component.scss'],
  imports: [RouterLink, DatePipe, TagFieldComponent]
})
export class UnqueriedTablesComponent implements OnInit, OnDestroy {

  all: SchemaUsage[] = [];

  /**
   * The schemas the scope leaves, which is what every count and note on this
   * page describes.
   *
   * <p>A field rebuilt in {@link applyScope}, not a getter: change detection
   * would otherwise re-filter and re-total the whole page on every pass. The
   * sibling list pages carry the same note.
   */
  visible: SchemaUsage[] = [];
  counts = totals([]);
  loading = true;
  failed = false;

  /** Whether the page is showing past its scope. */
  showExcluded = false;

  /** How many schemas the scope is currently keeping out. */
  excludedCount = 0;

  /** True when no scope is set at all, so nothing is being subtracted. */
  unscoped = true;

  /**
   * What the capture can actually support, in place of the hedge this page
   * used to carry. "Either nothing uses the schema, or its traffic was never
   * recorded" named two possibilities and could not tell them apart; these
   * two facts are how it tells them apart (sqlx-n01).
   */
  window: ObservedWindow | null = null;
  capacity: CoverageVerdict | null = null;
  protected readonly SEEN_FORMAT = SEEN_FORMAT;

  private readonly subscriptions = new Subscription();
  private scope: ScopeTags = NO_SCOPE_TAGS;

  /**
   * Schema name (lower-cased) → its tag names.
   *
   * <p>The scope is expressed in tags, and `/schema-tree` does not carry them,
   * so they are fetched alongside — one batched call for the whole page, the
   * same way `/tables` does it.
   */
  private schemaTags = new Map<string, string[]>();

  constructor(private schemaLensService: SchemaLensService,
              private sqlService: AnalyzedSqlService,
              private coverageService: CoverageService,
              private environmentService: EnvironmentService,
              private route: ActivatedRoute,
              private router: Router) {
  }

  ngOnInit() {
    // Tracked rather than read once: the scope is written by controls on other
    // pages and carried here by the router, so a snapshot read would leave this
    // page showing one thing while its own URL said another (sqlx-uxo).
    this.subscriptions.add(this.route.queryParamMap.subscribe(params => {
      this.showExcluded = params.get(EXCLUDED_PARAM) === '1';
      this.scope = decodeScopeTags(params);
      this.unscoped = isUnscoped(this.scope);
      this.applyScope();
    }));
    // Both halves belong to the selected environment, so both are re-read when
    // it changes. Switching without this would leave one environment's tables
    // qualified by another's capture, which is worse than saying nothing.
    this.subscriptions.add(this.environmentService.current$.subscribe(() => this.load()));
  }

  ngOnDestroy() {
    this.subscriptions.unsubscribe();
  }

  /**
   * Each listed table's tags, keyed `schema.table`, so rows can be tagged in
   * place (sqlx-0d8.4). One batched request for every row: a tag field left to
   * fetch its own would fire one request per row, and this list can be long.
   * Null until it answers; the fields render only then, so none fetches.
   * Every row gets its own array, kept stable: a fresh `[]` per render would
   * overwrite a tag a field had just added.
   */
  tableTags: Map<string, Tag[]> | null = null;

  protected readonly tableTarget = tableTarget;

  /** A row's tags; a row the batch did not cover gets its own array, stored, so it stays stable. */
  rowTags(schema: string, table: string): Tag[] {
    const key = tableKey(schema, table);
    let tags = this.tableTags?.get(key);
    if (!tags) {
      tags = [];
      this.tableTags?.set(key, tags);
    }
    return tags;
  }

  onRowTagsChanged(schema: string, table: string, tags: Tag[]) {
    this.tableTags?.set(tableKey(schema, table), tags);
  }

  private loadTableTags() {
    this.tableTags = null;
    const rows = this.all.flatMap(usage =>
      [...usage.unqueried, ...usage.dormant].map(table => ({schema: usage.schema, table: table.name})));
    if (!rows.length) { this.tableTags = new Map(); return; }
    this.sqlService.getTagsForTargets(rows.map(row => tableTarget(row.schema, row.table))).subscribe({
      next: answers => this.tableTags = new Map(rows.map((row, at) => [tableKey(row.schema, row.table), answers[at] ?? []])),
      error: () => this.tableTags = new Map(rows.map(row => [tableKey(row.schema, row.table), []]))
    });
  }

  private load() {
    this.loading = true;
    this.failed = false;
    this.schemaLensService.getSchemaTree().subscribe({
      next: tree => {
        this.all = unqueriedBySchema(tree);
        this.loading = false;
        this.applyScope();
        this.loadSchemaTags();
        this.loadTableTags();
      },
      // an empty list would read as "nothing is unqueried", which is a strong
      // and probably wrong claim to make out of a failed request
      error: () => { this.failed = true; this.loading = false; }
    });
    // A failure here leaves both null and the note simply says less. Coverage
    // qualifies this page's claim; it must never be able to break it.
    this.window = null;
    this.capacity = null;
    this.coverageService.getCoverage().subscribe({
      next: report => {
        this.window = sharedWindow(report);
        this.capacity = worstVerdict(findings(report, 'DIGEST_CAPACITY'));
      },
      error: () => { this.window = null; this.capacity = null; }
    });
  }

  /** The tags of every schema on the page, batched into one call. */
  private loadSchemaTags() {
    const names = this.all.map(usage => usage.schema).filter(Boolean);
    if (!names.length) { return; }
    this.sqlService.getTagsForTargets(names.map(schemaTarget)).subscribe({
      next: byIndex => {
        this.schemaTags = new Map(names.map((name, index) =>
          [name.toLowerCase(), (byIndex[index] ?? []).map(tag => tag.name)]));
        this.applyScope();
      },
      // Without the tags nothing is excluded, so the page shows everything —
      // which is the safe direction: too much, rather than a scope it only
      // appears to have applied.
      error: () => { /* keep the empty map */ }
    });
  }

  private tagsOf(usage: SchemaUsage): string[] {
    return this.schemaTags.get(usage.schema.toLowerCase()) ?? [];
  }

  private applyScope() {
    const inScope = this.all.filter(usage => matchesScopeTags(this.tagsOf(usage), this.scope));
    this.excludedCount = this.all.length - inScope.length;
    this.visible = this.showExcluded ? this.all : inScope;
    this.counts = totals(this.visible);
  }

  /**
   * Written with `replaceUrl` so looking past the scope and back does not stack
   * history entries — the back button should leave the page, not walk through
   * view states.
   */
  setShowExcluded(show: boolean) {
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: {[EXCLUDED_PARAM]: show ? '1' : null},
      queryParamsHandling: 'merge',
      replaceUrl: true
    });
  }
}
