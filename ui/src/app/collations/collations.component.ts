import {Component, OnDestroy, OnInit} from '@angular/core';
import {ActivatedRoute, Router, RouterLink} from '@angular/router';
import {Subscription} from 'rxjs';
import {GLOSSARY} from '../glossary';
import {AnalyzedSqlService} from '../analyzed-sql.service';
import {EnvironmentService} from '../environment.service';
import {decodeScopeTags, matchesScopeTags, NO_SCOPE_TAGS, ScopeTags} from '../scope-tags';
import {schemaTarget} from '../tag-path';
import {
  CollationReport, CollationsService, decodeEncodingFilter, decodeOpenTables, encodeEncodingFilter, encodeOpenTables,
  EncodingFilter, encodingChoices, EncodingGroup, groupByEncoding, isMixed, joinMatchesFilter, JoinEncodingMismatch, matchesEncodingFilter,
  NO_ENCODING_FILTER, TableEncoding, tableKey
} from './collations';

function csv(raw: string | null): string[] {
  return raw ? raw.split(',').map(value => value.trim().toLowerCase()).filter(Boolean) : [];
}

/**
 * Tables grouped by the character sets and collations of their columns, the
 * tables that mix them, and the joins in the captured queries whose two sides
 * differ (sqlx-n5j.5). A charset conversion is planned from this page: which
 * tables are already on the target, which are not, and which joins break an
 * index today because of it.
 *
 * <p>Every filter is in the URL (see collations.ts), and the cross-cutting
 * schema scope — `schemas`, `excludeSchemas`, and the schema-tag scope — is
 * honoured as the other list pages honour it.
 */
@Component({
  selector: 'app-collations',
  templateUrl: './collations.component.html',
  styleUrls: ['./collations.component.scss'],
  imports: [RouterLink]
})
export class CollationsComponent implements OnInit, OnDestroy {
  readonly glossary = GLOSSARY;
  protected readonly isMixed = isMixed;

  report: CollationReport | null = null;
  loading = true;
  failed = false;

  filter: EncodingFilter = NO_ENCODING_FILTER;
  /** Tables expanded to list their columns, by `schema.table`; in the URL as var-open. */
  open = new Set<string>();
  protected readonly tableKey = tableKey;

  // Rebuilt in applyFilter rather than computed in template getters, so change
  // detection does not regroup the whole schema on every pass.
  groups: EncodingGroup[] = [];
  joins: JoinEncodingMismatch[] = [];
  choices: {charsets: string[]; collations: string[]} = {charsets: [], collations: []};
  /** Tables in scope; what the counts in the header are out of. */
  inScope: TableEncoding[] = [];
  shownTables = 0;
  mixedCount = 0;
  noTextCount = 0;

  private scopeTags: ScopeTags = NO_SCOPE_TAGS;
  private schemas: string[] = [];
  private excludeSchemas: string[] = [];
  /** Schema name (lower-cased) → its tag names, for the tag scope. */
  private schemaTags = new Map<string, string[]>();
  private readonly subscriptions = new Subscription();

  constructor(private collationsService: CollationsService,
              private sqlService: AnalyzedSqlService,
              private environmentService: EnvironmentService,
              private route: ActivatedRoute,
              private router: Router) { }

  ngOnInit() {
    this.subscriptions.add(this.route.queryParamMap.subscribe(params => {
      this.filter = decodeEncodingFilter(params);
      this.open = decodeOpenTables(params);
      this.scopeTags = decodeScopeTags(params);
      this.schemas = csv(params.get('schemas'));
      this.excludeSchemas = csv(params.get('excludeSchemas'));
      this.applyFilter();
    }));
    this.subscriptions.add(this.environmentService.current$.subscribe(() => this.load()));
  }

  ngOnDestroy() {
    this.subscriptions.unsubscribe();
  }

  private load() {
    this.loading = true;
    this.failed = false;
    this.report = null;
    this.applyFilter();
    this.collationsService.getReport().subscribe({
      next: report => {
        this.report = report;
        this.loading = false;
        this.applyFilter();
        this.loadSchemaTags(report);
      },
      // an empty page would read as "every table agrees", which a failed request cannot say
      error: () => { this.failed = true; this.loading = false; }
    });
  }

  /** The tags of every schema on the page, batched into one call. */
  private loadSchemaTags(report: CollationReport) {
    const names = [...new Set(report.tables.map(table => table.schema).filter(Boolean))];
    if (!names.length) { return; }
    this.sqlService.getTagsForTargets(names.map(schemaTarget)).subscribe({
      next: byIndex => {
        this.schemaTags = new Map(names.map((name, index) =>
          [name.toLowerCase(), (byIndex[index] ?? []).map(tag => tag.name)]));
        this.applyFilter();
      },
      // without the tags nothing is excluded by tag: too much, never a scope only appearing applied
      error: () => { /* keep the empty map */ }
    });
  }

  private inScopeSchema(schema: string): boolean {
    const name = (schema ?? '').toLowerCase();
    if (this.schemas.length && !this.schemas.includes(name)) { return false; }
    if (this.excludeSchemas.includes(name)) { return false; }
    return matchesScopeTags(this.schemaTags.get(name) ?? [], this.scopeTags);
  }

  private applyFilter() {
    const tables = this.report?.tables ?? [];
    this.inScope = tables.filter(table => this.inScopeSchema(table.schema));
    this.choices = encodingChoices(this.inScope);
    const shown = this.inScope.filter(table => matchesEncodingFilter(table, this.filter));
    this.groups = groupByEncoding(shown);
    this.shownTables = shown.filter(table => table.textColumns > 0).length;
    this.mixedCount = this.inScope.filter(isMixed).length;
    this.noTextCount = this.inScope.filter(table => !table.textColumns).length;
    this.joins = (this.report?.joins ?? [])
      .filter(join => this.inScopeSchema(join.left.schema) || this.inScopeSchema(join.right.schema))
      .filter(join => joinMatchesFilter(join, this.filter));
  }

  setFilter(change: Partial<EncodingFilter>) {
    this.filter = {...this.filter, ...change};
    this.applyFilter();
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: encodeEncodingFilter(this.filter),
      queryParamsHandling: 'merge',
      replaceUrl: true
    });
  }

  isOpen(table: TableEncoding): boolean {
    return this.open.has(tableKey(table));
  }

  toggle(table: TableEncoding) {
    const open = new Set(this.open);
    const key = tableKey(table);
    if (open.has(key)) { open.delete(key); } else { open.add(key); }
    this.open = open;
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: encodeOpenTables(open),
      queryParamsHandling: 'merge',
      replaceUrl: true
    });
  }

  clearFilter() {
    this.setFilter(NO_ENCODING_FILTER);
  }

  filtered(): boolean {
    return !!this.filter.name.trim() || !!this.filter.charset || !!this.filter.collation || this.filter.mixedOnly;
  }

  /** A table's columns not on its baseline encoding, as "name (collation)". */
  outlierText(table: TableEncoding): string {
    return table.outliers.map(o => `${o.column} (${o.collation ?? o.characterSet ?? '?'})`).join(', ');
  }
}
