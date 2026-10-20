import {Component, OnInit} from '@angular/core';
import {ActivatedRoute, Router, RouterLink} from '@angular/router';
import {DecimalPipe} from '@angular/common';
import {Highlight} from 'ngx-highlightjs';
import {
  ColumnUsage,
  CoQueriedTable,
  ForeignKeyDetail,
  IndexUsage,
  SchemaLensService,
  TableStats,
  ViewColumnMeta
} from '../schema-lens.service';
import {DurationPipe} from '../duration.pipe';
import {TagFieldComponent} from '../tag-field/tag-field.component';
import {parseColumnRef, parseTableRef} from '../refs';
import {QueryHit, QuerySearchService} from '../query-search.service';
import {AnalyzedSqlService} from '../analyzed-sql.service';
import {tableKey} from '../query-scope.service';
import {buildSearchRequest, decodeSearchParams, defaultSearchState, encodeSearchParams, SearchUrlState, UNCONSTRAINED_TAG_SCOPE} from '../search-url';
import {SqlSummaryComponent} from '../sql-summary/sql-summary.component';
import {tableTarget} from '../tag-path';

/** Queries fetched per page for the table's query list. */
const QUERY_PAGE_SIZE = 25;

@Component({
  selector: 'app-table-lens',
  templateUrl: './table-lens.component.html',
  styleUrls: ['./table-lens.component.scss'],
  imports: [RouterLink, DurationPipe, TagFieldComponent, DecimalPipe, Highlight, SqlSummaryComponent]
})
export class TableLensComponent implements OnInit {
  protected readonly tableTarget = tableTarget;

  schema = '';
  table = '';

  stats: TableStats | null = null;
  columns: ColumnUsage[] = [];
  coQueried: CoQueriedTable[] = [];
  foreignKeys: ForeignKeyDetail[] = [];
  /** The table's indexes, with how queries hint each, then hinted ones it lacks. */
  indexUsage: IndexUsage[] = [];
  isView = false;

  // ── does this table exist? ──
  /**
   * `/metadata/table/{s}/{t}` answers a table the connected schema has never
   * heard of with a **null body**, not a 404 — so `structure === null` is the
   * source of truth for "no such table", and an HTTP failure stays a separate
   * thing (`metadataFailed`). Absence must never be inferred from an outage.
   */
  private structureResolved = false;
  private statsResolved = false;
  /** The metadata call failed outright; nothing can be concluded from it. */
  metadataFailed = false;
  /** The connected schema has this table. */
  private knownToSchema = false;

  /** Base tables this view selects from (views only). */
  underlyingTables: { schema: string; table: string }[] = [];
  /** Per-column lineage (views only), keyed by normalized column name. */
  private viewLineage = new Map<string, ViewColumnMeta>();

  columnFilter: 'all' | 'used' | 'keys' = 'all';

  // ── queries card ──
  queries: QueryHit[] = [];
  queryTotal = 0;
  queriesLoading = false;
  /** Drill-down: only queries this column joins on or filters by. */
  drillColumn: string | null = null;
  /** Drill-down: only queries whose index hints name this index (`?indexes=`). */
  drillIndex: string | null = null;
  /** Drill-down: only queries touching this table as well as the current one. */
  joinWith: string | null = null;

  /**
   * Collapsed by default — the relationship cards answer "what is related"
   * far more often than "what are the constraint names", and the detail is
   * one click away. Reflected in the URL as presentation state.
   */
  fkOpen = false;
  coOpen = false;

  // ── parity with the legacy table view ──
  remarks: string | null = null;

  /** Column name (lower-cased) to its DDL comment, empties already dropped. */
  private columnComments = new Map<string, string>();
  showCreate = false;
  createStatement: string | null = null;
  createLoading = false;

  constructor(private route: ActivatedRoute,
              private router: Router,
              private schemaLensService: SchemaLensService,
              private querySearchService: QuerySearchService,
              private analyzedSqlService: AnalyzedSqlService) { }

  ngOnInit() {
    this.route.paramMap.subscribe(params => {
      const schema = params.get('schema') || '';
      const table = params.get('name') || '';
      const changed = schema !== this.schema || table !== this.table;
      this.schema = schema;
      this.table = table;
      if (changed) {
        this.showCreate = false;
        this.createStatement = null;
        this.load();
      }
    });
    // drill-downs and collapse state live in the URL, so back/forward and a
    // shared link reproduce the view without refetching the table itself
    this.route.queryParamMap.subscribe(params => {
      const state = decodeSearchParams(params);
      this.drillColumn = state.columns[0] ?? null;
      this.drillIndex = state.indexes[0] ?? null;
      this.joinWith = state.scope.tables.find(key => key !== this.selfKey()) ?? null;
      this.fkOpen = params.get('var-fk') === '1';
      this.coOpen = params.get('var-co') === '1';
      this.runQuerySearch();
    });
  }

  private selfKey(): string {
    return tableKey(this.schema, this.table);
  }

  // ── queries ──

  private runQuerySearch() {
    if (!this.schema || !this.table) { return; }
    const state = decodeSearchParams(this.route.snapshot.queryParamMap);
    this.queriesLoading = true;
    this.querySearchService.search(buildSearchRequest(
      { ...state, scope: this.queryScope() },
      UNCONSTRAINED_TAG_SCOPE,
      { limit: QUERY_PAGE_SIZE, offset: 0 })).subscribe({
        next: result => {
          this.queries = result.results;
          this.queryTotal = result.total;
          this.queriesLoading = false;
        },
        error: () => { this.queries = []; this.queryTotal = 0; this.queriesLoading = false; }
      });
  }

  /**
   * The tables the Queries card searches: always this table, and with a join
   * drill-down the other table too, both required.
   *
   * <p>Built from the page's own table rather than taken from `?tables=`.
   * That param travels between pages (scope-url.ts), so it can arrive naming
   * only some other table; searched as-is, "joined with jobs" listed every
   * query on jobs, including ones that never touch this table.
   */
  private queryScope(): SearchUrlState['scope'] {
    return this.joinWith
      ? { schemas: [], tables: [this.selfKey(), this.joinWith], tableMode: 'ALL' }
      : { schemas: [], tables: [this.selfKey()], tableMode: 'ANY' };
  }

  /** Merge a patch into the URL without a history entry (the workbench pattern). */
  private patchUrl(queryParams: Record<string, string | null>) {
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams,
      queryParamsHandling: 'merge',
      replaceUrl: true
    });
  }

  /** Only join/criteria columns can constrain a query, so only those drill. */
  isDrillable(column: ColumnUsage): boolean {
    return column.roles.includes('join') || column.roles.includes('criteria');
  }

  drillIntoColumn(column: ColumnUsage) {
    if (!this.isDrillable(column)) { return; }
    const reference = `${this.selfKey()}.${column.name}`;
    this.patchUrl({ columns: this.drillColumn === reference ? null : reference });
  }

  /** Only an index some query hints has queries to show. */
  isIndexDrillable(index: IndexUsage): boolean {
    return index.queries > 0;
  }

  indexReference(index: IndexUsage): string {
    return `${this.selfKey()}.${index.name}`;
  }

  drillIntoIndex(index: IndexUsage) {
    if (!this.isIndexDrillable(index)) { return; }
    const reference = this.indexReference(index);
    this.patchUrl({ indexes: this.drillIndex === reference ? null : reference });
  }

  /** The active index drill-down names a hinted index this table does not have. */
  drillIndexMissing(): boolean {
    return !!this.drillIndex && this.indexUsage.some(index =>
      !index.exists && this.indexReference(index) === this.drillIndex);
  }

  drillIntoJoin(otherSchema: string, otherTable: string) {
    const other = tableKey(otherSchema, otherTable);
    const active = this.joinWith === other;
    this.patchUrl({
      tables: active ? null : [this.selfKey(), other].join(','),
      tableMode: active ? null : 'ALL'
    });
  }

  clearDrillDown() {
    this.patchUrl({ columns: null, indexes: null, tables: null, tableMode: null });
  }

  toggleFk() { this.patchUrl({ 'var-fk': this.fkOpen ? null : '1' }); }

  toggleCo() { this.patchUrl({ 'var-co': this.coOpen ? null : '1' }); }

  /**
   * The same search, opened in the workbench. A pure param translation —
   * the lens speaks the shared vocabulary, so nothing has to be re-mapped.
   */
  /**
   * The graph of the queries touching this table: a search-sourced graph
   * scoped to it, the same URL the workbench's "graph results" writes.
   */
  graphParams(): Record<string, string> {
    return {
      source: 'search',
      ...encodeSearchParams({
        ...defaultSearchState(),
        scope: { schemas: [], tables: [this.selfKey()], tableMode: 'ALL' }
      })
    };
  }

  workbenchParams(): Record<string, string> {
    const state = decodeSearchParams(this.route.snapshot.queryParamMap);
    return encodeSearchParams({ ...state, scope: this.queryScope() });
  }

  /** Legacy parity: the CREATE TABLE statement, fetched on first open. */
  toggleCreate() {
    this.showCreate = !this.showCreate;
    if (this.showCreate && this.createStatement === null) {
      this.createLoading = true;
      this.analyzedSqlService.getTableCreateStatement(this.schema, this.table).subscribe({
        next: create => { this.createStatement = create; this.createLoading = false; },
        error: () => { this.createLoading = false; }
      });
    }
  }

  private load() {
    this.stats = null;
    this.columns = [];
    this.coQueried = [];
    this.foreignKeys = [];
    this.indexUsage = [];
    this.isView = false;
    this.underlyingTables = [];
    this.viewLineage = new Map();
    // reset the existence verdict too, or navigating table → table renders the
    // previous table's answer over the new table's pending requests
    this.structureResolved = false;
    this.statsResolved = false;
    this.metadataFailed = false;
    this.knownToSchema = false;
    this.schemaLensService.getTableStats(this.schema, this.table).subscribe({
      next: stats => { this.stats = stats; this.statsResolved = true; },
      error: () => { this.stats = null; }
    });
    this.schemaLensService.getTableColumns(this.schema, this.table).subscribe(columns => this.columns = columns);
    this.schemaLensService.getTableIndexes(this.schema, this.table).subscribe({
      next: indexes => this.indexUsage = indexes || [],
      error: () => { this.indexUsage = []; }
    });
    this.schemaLensService.getCoQueried(this.schema, this.table).subscribe(coQueried => this.coQueried = coQueried);
    this.schemaLensService.getForeignKeys(this.schema, this.table).subscribe(fks => this.foreignKeys = fks);
    this.schemaLensService.getTableStructure(this.schema, this.table)
      .subscribe({
        next: structure => {
          this.structureResolved = true;
          this.knownToSchema = !!structure;
          this.isView = structure?.type?.toUpperCase() === 'VIEW';
          // refs arrive as "schema.table" strings; the old object-shaped filter
          // read undefined on both fields and dropped every one of them
          this.underlyingTables = (structure?.underlyingTables || [])
            .map(ref => parseTableRef(ref))
            .filter((ref): ref is { schema: string; table: string } => !!ref.schema && !!ref.table);
          this.remarks = structure?.remarks?.trim() || null;
          // Joined by name, because the column table is built from ColumnUsage
          // and the comment lives on the metadata payload. Empty strings are
          // dropped here rather than at render: MySQL returns '' for "no
          // comment", and every column without one would otherwise get a blank
          // line (sqlx-gw5).
          this.columnComments = new Map((structure?.columns || [])
            .filter(column => (column.remarks || '').trim())
            .map(column => [column.columnName.toLowerCase(), column.remarks!.trim()]));
          this.viewLineage = new Map((structure?.viewColumns || [])
            .map(column => [column.name.toLowerCase(), column]));
        },
        error: () => { this.metadataFailed = true; }
      });
  }

  /**
   * Nothing knows this table: the connected schema has no such table *and* no
   * query has ever referenced it. Both halves are load-bearing — against a
   * CSV/Parquet source there is no schema to introspect, so a bare
   * `structure === null` would declare every table in the app missing.
   *
   * This rests on `/table/{s}/{t}/stats` answering *zeros* rather than an
   * error for a table nothing knows: `AnalyzedSqlRepository.getTableQueries`
   * reads an `ImmutableListMultimap`, whose `get()` on an absent key is an
   * empty list. If that endpoint ever starts erroring, `statsResolved` stays
   * false forever and this whole state becomes silently unreachable — the
   * unit tests flush a synthetic `TableStats` and cannot see it.
   *
   * One residual false positive, accepted: a real table in a schema that was
   * never introspected *and* that no query touches reports not-found. The
   * conjunction cannot separate it from a table that does not exist, and such
   * a table is only reachable by a hand-typed or broken URL — the same
   * population this state exists to serve.
   */
  notFound(): boolean {
    return this.structureResolved && this.statsResolved
      && !this.knownToSchema && this.stats?.distinctQueries === 0;
  }

  /**
   * Referenced by queries but absent from the schema. The structure cards are
   * empty for a reason here, and saying so beats an unexplained blank.
   */
  unintrospected(): boolean {
    return this.structureResolved && this.statsResolved && !this.knownToSchema && !this.notFound();
  }

  /**
   * The `?columns=` drill-down names something this table does not have —
   * typically a mis-parsed column reference followed in from another page.
   * Only answerable once the column list is loaded; with no metadata at all
   * there is nothing to check against, so stay quiet rather than guess.
   */
  drillColumnUnknown(): boolean {
    if (!this.drillColumn || this.columns.length === 0) { return false; }
    const ref = parseColumnRef(this.drillColumn);
    const sameTable = ref.table === null || (ref.schema === null
      ? ref.table.toLowerCase() === this.table.toLowerCase()
      : tableKey(ref.schema, ref.table) === this.selfKey());
    if (!sameTable) { return true; }
    return !this.columns.some(column => column.name.toLowerCase() === ref.column.toLowerCase());
  }

  /** Split a lineage source string for display and linking. */
  sourceParts(source: string): { schema: string | null; table: string | null; column: string } {
    return parseColumnRef(source);
  }

  lineageFor(columnName: string): ViewColumnMeta | null {
    return this.viewLineage.get(columnName.toLowerCase()) ?? null;
  }

  /**
   * The schema's own comment for a column, or null.
   *
   * <p>Deliberately not merged with tags or notes. A DDL comment is written by
   * whoever owns the schema and is a statement about the object; a tag is
   * written here by a colleague. Showing them as one thing would erase which
   * is authoritative (sqlx-gw5).
   */
  commentFor(column: ColumnUsage): string | null {
    return this.columnComments.get(column.name.toLowerCase()) ?? null;
  }

  visibleColumns(): ColumnUsage[] {
    switch (this.columnFilter) {
      case 'used': return this.columns.filter(column => column.queries > 0);
      case 'keys': return this.columns.filter(column => column.primaryKey || column.foreignKey);
      default: return this.columns;
    }
  }

  outgoingCount(): number {
    return this.foreignKeys.filter(fk => fk.direction === 'out').length;
  }

  incomingCount(): number {
    return this.foreignKeys.filter(fk => fk.direction === 'in').length;
  }

  readPercent(): number | null {
    if (!this.stats) { return null; }
    const total = this.stats.readExecutions + this.stats.writeExecutions;
    return total === 0 ? null : Math.round(this.stats.readExecutions / total * 100);
  }

  maxColumnQueries(): number {
    return Math.max(1, ...this.columns.map(column => column.queries));
  }

  fmtExecutions(value: number): string {
    if (value >= 1_000_000) { return (value / 1_000_000).toFixed(1) + 'M'; }
    if (value >= 1_000) { return Math.round(value / 1_000) + 'K'; }
    return String(value);
  }

  fmtMs(value: number | null): string {
    if (value === null || value === undefined) { return '—'; }
    return value >= 1000 ? (value / 1000).toFixed(1) + 's' : value.toFixed(1) + 'ms';
  }
}
