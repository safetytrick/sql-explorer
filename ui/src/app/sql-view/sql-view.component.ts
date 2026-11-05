import {GLOSSARY} from '../glossary';
import {formatPageTitle} from '../page-title';
import {ChangeDetectorRef, Component, OnInit} from '@angular/core';
import {AnalyzedSqlService} from '../analyzed-sql.service';
import {ActivatedRoute} from '@angular/router';
import {Title} from '@angular/platform-browser';
import {DatePipe, DecimalPipe, SlicePipe} from '@angular/common';
import {RouterLink} from '@angular/router';
import {SqlHighlightDirective} from '../sql-highlight/sql-highlight.directive';
import {TagFieldComponent} from '../tag-field/tag-field.component';
import {PicoViewComponent} from '../pico-view/pico-view.component';
import {ColumnLinkComponent} from '../column-link/column-link.component';
import {SqlProfile, TableStructure} from '../api-types';
import {parseTableRef} from '../refs';
import {MergeVariant, mergeVariants, perfDigestsOf, repeatSpread, RepeatSpread} from '../queries-workbench/merge-group';
import {buildIndexPanels, TableIndexPanel} from './index-relevance';
import {SEEN_FORMAT} from '../seen-format';
import {QueryExamplesComponent} from '../query-examples/query-examples.component';
import {queryTarget} from '../tag-path';

@Component({
  selector: 'app-sql-view',
  templateUrl: './sql-view.component.html',
  styleUrls: ['./sql-view.component.scss'],
  providers: [AnalyzedSqlService],
  imports: [RouterLink, DecimalPipe, SlicePipe, SqlHighlightDirective, TagFieldComponent, PicoViewComponent, ColumnLinkComponent,
    QueryExamplesComponent, DatePipe]
})
export class SqlViewComponent implements OnInit {
  /** Hover definitions for the runtime metrics (glossary.ts, sqlx-tcv.1). */
  readonly glossary = GLOSSARY;

  protected readonly queryTarget = queryTarget;

  /** @see SEEN_FORMAT */
  readonly SEEN_FORMAT = SEEN_FORMAT;

  query: SqlProfile | null = null;
  /** Structures for the query's tables, as they resolve. */
  tables: TableStructure[] = [];
  loading = false;
  notFound = false;
  copied: string | null = null;
  /** Recomputed when the query loads or a table structure resolves — not per tick. */
  indexPanels: TableIndexPanel[] = [];

  constructor(private sqlService: AnalyzedSqlService, private route: ActivatedRoute,
              private changeDetectorRef: ChangeDetectorRef,
              private titleService: Title) { }

  ngOnInit() {
    this.route.params.subscribe(params => this.getSql(params['id']));
  }

  getSql(id: string) {
    this.query = null;
    this.tables = [];
    this.indexPanels = [];
    this.loading = true;
    this.notFound = false;
    this.sqlService.getSql(id).subscribe({
      next: data => {
        this.query = data;
        this.loading = false;
        this.loadTableStructures(data);
        this.titleService.setTitle(formatPageTitle('Query · ' + data.tables.map(ref => parseTableRef(ref).table).join(', ')));
      },
      error: () => {
        this.loading = false;
        this.notFound = true;
        this.titleService.setTitle(formatPageTitle('Query not found'));
      }
    });
  }

  private loadTableStructures(data: SqlProfile) {
    data.tables.forEach(tableRef => {
      const ref = parseTableRef(tableRef);
      const schema = ref.schema ?? data.schemaName;
      this.sqlService.getTableStructure(schema, ref.table).subscribe(structure => {
        // the metadata endpoint returns an empty body for tables the loaded
        // schema doesn't know (dropped tables, other servers, misextracted
        // references) — those have nothing to render
        if (structure) {
          this.tables.push(structure);
          this.indexPanels = this.computeIndexPanels();
          this.changeDetectorRef.markForCheck();
        }
      });
    });
  }

  // ── what the query constrains ──

  /**
   * Every column the query joins on, filters by, or groups by — the columns an
   * index could actually serve. Select-list columns are deliberately excluded:
   * they cannot drive index selection.
   */
  private constrainingColumns(): string[] {
    if (!this.query) { return []; }
    // groupByColumns is null — not empty — when SelectColumnAnalyzer never ran
    // or threw, which is the case for ~5% of profiles.
    return [
      ...(this.query.criteriaColumns ?? []),
      ...(this.query.groupByColumns ?? []),
      ...(this.query.joinColumns ?? []).flat()
    ];
  }

  private computeIndexPanels(): TableIndexPanel[] {
    return this.query
      ? buildIndexPanels(this.tables, this.constrainingColumns(), this.query.schemaName)
      : [];
  }

  // ── performance strip ──

  /**
   * Column types for every table this query touches, flattened, to type the
   * example form's inputs. `typeName` is the database's own spelling
   * (VARCHAR, BIGINT, DATETIME) rather than the JDBC enum, which is what the
   * form matches on.
   */
  get exampleColumnTypes(): { name: string; type: string }[] {
    return this.tables.flatMap(table => (table.columns || [])
      .map(column => ({name: column.columnName, type: column.typeName || column.type})));
  }

  get metrics() {
    return this.query?.runtimeMetrics ?? null;
  }

  /** Rows returned ÷ rows read. Low means the engine is reading to discard. */
  get selectivityPct(): number | null {
    const m = this.metrics;
    if (!m || !m.rowsScanned) { return null; }
    return (m.rowsSent / m.rowsScanned) * 100;
  }

  /** Rows read for each row returned — the "reads 1000× what it returns" signal. */
  get scanRatio(): number | null {
    const m = this.metrics;
    if (!m || !m.rowsSent) { return null; }
    return m.rowsScanned / m.rowsSent;
  }

  /** Only-when-true conditions, so an empty row means nothing is alarming. */
  get warningBadges(): { label: string; title: string }[] {
    const badges: { label: string; title: string }[] = [];
    const m = this.metrics;
    if (m?.fullTableScan) {
      badges.push({ label: 'full scan', title: 'at least one execution read a table end to end' });
    }
    if (m && m.tempDiskTables > 0) {
      badges.push({ label: 'temp → disk', title: `${m.tempDiskTables} temporary tables spilled to disk` });
    }
    if (m && m.sortMergePasses > 0) {
      badges.push({ label: 'sort spill', title: `${m.sortMergePasses} sort merge passes — the sort did not fit in memory` });
    }
    if (m && m.errors > 0) {
      badges.push({ label: `${m.errors} errors`, title: 'executions that returned an error' });
    }
    if (this.query?.truncated) {
      badges.push({ label: 'truncated', title: 'the captured statement was cut off, so analysis is partial' });
    }
    if (this.query?.queryActivityStatus === 'DORMANT') {
      badges.push({ label: 'dormant', title: 'not seen recently' });
    }
    return badges;
  }

  get analyzerWarning(): boolean {
    return (this.query?.mismatchedJoins.length ?? 0) > 0;
  }

  /** The MySQL digests behind this profile; see {@link perfDigestsOf}. */
  get perfDigests(): string[] {
    return perfDigestsOf(this.query);
  }

  // ── merge group (folds the many-perf-digest case) ──

  /** The repeat range across this group's members, or null when they agree. */
  get repeatSpread(): RepeatSpread | null {
    if (!this.query) { return null; }
    return repeatSpread(this.query.repeatedBlocks, this.query.mergedChildren);
  }

  get mergeVariants(): MergeVariant[] {
    if (!this.query) { return []; }
    return mergeVariants(this.query.mergedChildren, this.query.repeatedBlocks);
  }

  copy(value: string) {
    navigator.clipboard.writeText(value).then(() => {
      this.copied = value;
      setTimeout(() => {
        if (this.copied === value) { this.copied = null; }
      }, 1500);
    }, () => this.copied = null);
  }

  fmtMs(picos: number | null | undefined): string {
    if (picos === null || picos === undefined) { return '—'; }
    const value = picos / 1_000_000_000;
    if (value >= 60000) { return (value / 60000).toFixed(1) + 'm'; }
    if (value >= 1000) { return (value / 1000).toFixed(1) + 's'; }
    return value.toFixed(1) + 'ms';
  }

  rows(value: number | null | undefined): string {
    if (value === null || value === undefined) { return '—'; }
    if (value >= 1_000_000) { return (value / 1_000_000).toFixed(1) + 'M'; }
    if (value >= 1_000) { return Math.round(value / 1_000) + 'K'; }
    return String(value);
  }
}
