import {GLOSSARY} from '../glossary';
import {formatPageTitle} from '../page-title';
import {ChangeDetectorRef, Component, OnInit} from '@angular/core';
import {AnalyzedSqlService} from '../analyzed-sql.service';
import {ActivatedRoute} from '@angular/router';
import {Title} from '@angular/platform-browser';
import {DatePipe, DecimalPipe, SlicePipe} from '@angular/common';
import {RouterLink} from '@angular/router';
import {SqlHighlightDirective} from '../sql-highlight/sql-highlight.directive';
import {constrainingInstances, instancesOf, Painted, paintSql, SchemaHint, TableColor} from '../sql-highlight/paint';
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
import {AnnotationService} from '../annotation.service';
import {AnnotationsViewComponent} from '../annotations-view/annotations-view.component';
import {GeneratorOrigin, generatorOrigin, generatorStatuses, GeneratorStatuses, originLabel, originRules} from '../generator-origin';

/** The SQL's colours for one index panel: the table's instances, and per column which of them constrain it. */
interface PanelColors {
  instances: TableColor[];
  /** Keyed by lowercased column name. */
  columns: Map<string, TableColor[]>;
}

const panelKey = (panel: TableIndexPanel) => `${panel.schema}.${panel.table}`.toLowerCase();

@Component({
  selector: 'app-sql-view',
  templateUrl: './sql-view.component.html',
  styleUrls: ['./sql-view.component.scss'],
  providers: [AnalyzedSqlService],
  imports: [RouterLink, DecimalPipe, SlicePipe, SqlHighlightDirective, TagFieldComponent, PicoViewComponent, ColumnLinkComponent,
    QueryExamplesComponent, DatePipe, AnnotationsViewComponent]
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
  /**
   * Column lists of the tables already loaded for the index card, handed to
   * the highlighter so it can place unqualified columns in a multi-table
   * query (sqlx-4vp). Only this page does it: it costs no request here, and
   * the other pages that show SQL don't load structures at all.
   */
  sqlSchema: SchemaHint[] | null = null;
  /** The SQL's table colours, echoed on the index card (sqlx-l9h). */
  private panelColors = new Map<string, PanelColors>();
  private painted: Painted | null = null;
  /**
   * The table instance last pointed at in the SQL, or null (sqlx-ee3). Not
   * written to the URL: it follows the pointer and is gone when the pointer
   * leaves, so there is no view to share.
   */
  hovered: TableColor | null = null;
  /** `indexPanels`, narrowed to `hovered` while there is one. */
  visiblePanels: TableIndexPanel[] = [];

  /**
   * Which generator wrote the statement, as far as the environment's confirmed
   * generators allow (sqlx-n5j.12): asserted, ambiguous or only a candidate.
   */
  origin: GeneratorOrigin | null = null;
  private statuses: GeneratorStatuses = new Map();
  protected readonly originLabel = originLabel;
  protected readonly originRules = originRules;

  constructor(private sqlService: AnalyzedSqlService, private route: ActivatedRoute,
              private changeDetectorRef: ChangeDetectorRef,
              private titleService: Title,
              private annotationService: AnnotationService) { }

  ngOnInit() {
    this.route.params.subscribe(params => this.getSql(params['id']));
    // the environment's confirmations, applied to the candidates when read
    this.annotationService.generators().subscribe({
      next: generators => { this.statuses = generatorStatuses(generators ?? []); this.updateOrigin(); },
      error: () => { /* without confirmations every match stays a candidate */ }
    });
  }

  private updateOrigin() {
    this.origin = this.query ? generatorOrigin(this.query.generatorCandidates, this.statuses) : null;
  }

  getSql(id: string) {
    this.query = null;
    this.tables = [];
    this.indexPanels = [];
    this.sqlSchema = null;
    this.panelColors = new Map();
    this.painted = null;
    this.hovered = null;
    this.visiblePanels = [];
    this.loading = true;
    this.notFound = false;
    this.sqlService.getSql(id).subscribe({
      next: data => {
        this.query = data;
        this.updateOrigin();
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
          this.refreshPanels();
          this.changeDetectorRef.markForCheck();
        }
      });
    });
  }

  // ── what the query constrains ──

  /**
   * How a criteria column is pattern-matched, if it is (sqlx-n5j.7): `like`,
   * `not like`, either marked `%…` when the pattern is known to lead with a
   * wildcard. Null for a column no LIKE touches.
   */
  likeLabel(column: string): string | null {
    const likes = (this.query?.criteriaComparisons ?? [])
      .filter(c => c.column === column && (c.kind === 'like' || c.kind === 'not like'));
    if (!likes.length) { return null; }
    const kinds = [...new Set(likes.map(c => c.kind))].sort().join(' / ');
    return likes.some(c => c.leadingWildcard === true) ? `${kinds} %…` : kinds;
  }

  /**
   * Every column the query joins on, filters by, groups by or sorts by — the
   * columns an index could actually serve. An index on the ORDER BY columns
   * lets MySQL read rows in order and skip the sort. Select-list columns are
   * deliberately excluded: they cannot drive index selection.
   */
  private constrainingColumns(): string[] {
    if (!this.query) { return []; }
    // groupByColumns and orderByColumns are null — not empty — when
    // SelectColumnAnalyzer never ran or threw (~5% of profiles), or the
    // profile predates the field.
    return [
      ...(this.query.criteriaColumns ?? []),
      ...(this.query.groupByColumns ?? []),
      ...(this.query.orderByColumns ?? []),
      ...(this.query.joinColumns ?? []).flat()
    ];
  }

  private refreshPanels() {
    if (!this.query) { return; }
    this.indexPanels = buildIndexPanels(this.tables, this.constrainingColumns(), this.query.schemaName);
    this.sqlSchema = this.tables.map(t => ({schema: t.schema, name: t.name, columns: (t.columns || []).map(c => c.columnName)}));
    const painted = this.painted = paintSql(this.query.query, this.sqlSchema);
    this.panelColors = new Map(this.indexPanels.map(panel => {
      const instances = instancesOf(painted, panel.schema, panel.table);
      const names = new Set([...panel.queryColumns, ...panel.indexes.flatMap(i => i.matched.map(c => c.toLowerCase()))]);
      const columns = new Map([...names].map(c => [c, constrainingInstances(painted, instances, c)]));
      return [panelKey(panel), {instances, columns}];
    }));
    this.visiblePanels = this.focusPanels();
  }

  // ── pointing at a table in the SQL narrows the index pane (sqlx-ee3) ──

  onTableHover(table: TableColor | null) {
    this.hovered = table;
    this.visiblePanels = this.focusPanels();
  }

  /**
   * Only the hovered instance's table, and within it only the indexes that
   * one of that instance's constrained columns reaches — in a self-join,
   * `p`'s indexes are not `a`'s. A CTE, a derived table or a table whose
   * structure did not load has no panel, so the pane stays whole rather than
   * going blank; so does a panel the instance constrains nothing in.
   */
  private focusPanels(): TableIndexPanel[] {
    const table = this.hovered;
    if (!table || table.kind !== 'table' || !this.painted) { return this.indexPanels; }
    const focused = this.indexPanels.flatMap(panel => {
      if (!this.instancesFor(panel).some(i => i.id === table.id)) { return []; }
      const mine = new Set(panel.queryColumns
        .filter(column => this.columnColors(panel, column).some(i => i.id === table.id)));
      const indexes = panel.indexes.filter(scored => scored.matched.some(m => mine.has(m.toLowerCase())));
      return [{...panel, indexes: indexes.length ? indexes : panel.indexes}];
    });
    return focused.length ? focused : this.indexPanels;
  }

  /** True while the pane shows less than every panel and index. */
  get focused(): boolean {
    return this.visiblePanels !== this.indexPanels;
  }

  // ── the SQL's colours on the index card ──

  /** Every instance of the panel's table in the SQL; more than one is a self-join. */
  instancesFor(panel: TableIndexPanel): TableColor[] {
    return this.panelColors.get(panelKey(panel))?.instances ?? [];
  }

  /** The instances that constrain a column of the panel's table. */
  columnColors(panel: TableIndexPanel, column: string): TableColor[] {
    return this.panelColors.get(panelKey(panel))?.columns.get(column.toLowerCase()) ?? [];
  }

  /** `tinted sqt-cN` when one instance owns the column; several show as swatches instead. */
  tintClass(colors: TableColor[]): string {
    return colors.length === 1 ? `tinted ${colors[0].cssClass}` : '';
  }

  /** Hover text naming the instance(s) behind a colour. */
  tintTitle(colors: TableColor[]): string {
    return colors.length ? 'constrained via ' + colors.map(c => c.label).join(', ') : '';
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
