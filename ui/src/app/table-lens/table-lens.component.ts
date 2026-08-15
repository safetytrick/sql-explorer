import {Component, OnInit} from '@angular/core';
import {ActivatedRoute, Router, RouterLink} from '@angular/router';
import {DecimalPipe} from '@angular/common';
import {Highlight} from 'ngx-highlightjs';
import {
  ColumnUsage,
  CoQueriedTable,
  ForeignKeyDetail,
  SchemaLensService,
  TableIndex,
  TableStats,
  ViewColumnMeta
} from '../schema-lens.service';
import {DurationPipe} from '../duration.pipe';
import {TagFieldComponent} from '../tag-field/tag-field.component';
import {parseColumnRef, parseTableRef} from '../refs';
import {QueryHit, QuerySearchService} from '../query-search.service';
import {AnalyzedSqlService} from '../analyzed-sql.service';
import {tableKey} from '../query-scope.service';
import {buildSearchRequest, decodeSearchParams, encodeSearchParams, UNCONSTRAINED_TAG_SCOPE} from '../search-url';

/** Queries fetched per page for the table's query list. */
const QUERY_PAGE_SIZE = 25;

@Component({
  selector: 'app-table-lens',
  templateUrl: './table-lens.component.html',
  styleUrls: ['./table-lens.component.scss'],
  imports: [RouterLink, DurationPipe, TagFieldComponent, DecimalPipe, Highlight]
})
export class TableLensComponent implements OnInit {

  schema = '';
  table = '';

  stats: TableStats | null = null;
  columns: ColumnUsage[] = [];
  coQueried: CoQueriedTable[] = [];
  foreignKeys: ForeignKeyDetail[] = [];
  indexes: TableIndex[] = [];
  isView = false;
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
    const tables = state.scope.tables.length ? state.scope.tables : [this.selfKey()];
    this.queriesLoading = true;
    this.querySearchService.search(buildSearchRequest(
      { ...state, scope: { schemas: [], tables, tableMode: this.joinWith ? 'ALL' : 'ANY' } },
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

  drillIntoJoin(otherSchema: string, otherTable: string) {
    const other = tableKey(otherSchema, otherTable);
    const active = this.joinWith === other;
    this.patchUrl({
      tables: active ? null : [this.selfKey(), other].join(','),
      tableMode: active ? null : 'ALL'
    });
  }

  clearDrillDown() {
    this.patchUrl({ columns: null, tables: null, tableMode: null });
  }

  toggleFk() { this.patchUrl({ 'var-fk': this.fkOpen ? null : '1' }); }

  toggleCo() { this.patchUrl({ 'var-co': this.coOpen ? null : '1' }); }

  /**
   * The same search, opened in the workbench. A pure param translation —
   * the lens speaks the shared vocabulary, so nothing has to be re-mapped.
   */
  workbenchParams(): Record<string, string> {
    const state = decodeSearchParams(this.route.snapshot.queryParamMap);
    const tables = state.scope.tables.length ? state.scope.tables : [this.selfKey()];
    return encodeSearchParams({
      ...state,
      scope: { schemas: [], tables, tableMode: this.joinWith ? 'ALL' : 'ANY' }
    });
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

  queryTime(hit: QueryHit): number | null {
    return hit.profile.runtimeMetrics ? hit.profile.runtimeMetrics.averageTime / 1_000_000_000 : null;
  }

  private load() {
    this.stats = null;
    this.columns = [];
    this.coQueried = [];
    this.foreignKeys = [];
    this.indexes = [];
    this.isView = false;
    this.underlyingTables = [];
    this.viewLineage = new Map();
    this.schemaLensService.getTableStats(this.schema, this.table).subscribe(stats => this.stats = stats);
    this.schemaLensService.getTableColumns(this.schema, this.table).subscribe(columns => this.columns = columns);
    this.schemaLensService.getCoQueried(this.schema, this.table).subscribe(coQueried => this.coQueried = coQueried);
    this.schemaLensService.getForeignKeys(this.schema, this.table).subscribe(fks => this.foreignKeys = fks);
    this.schemaLensService.getTableStructure(this.schema, this.table)
      .subscribe(structure => {
        this.indexes = structure?.indices || [];
        this.isView = structure?.type?.toUpperCase() === 'VIEW';
        // refs arrive as "schema.table" strings; the old object-shaped filter
        // read undefined on both fields and dropped every one of them
        this.underlyingTables = (structure?.underlyingTables || [])
          .map(ref => parseTableRef(ref))
          .filter((ref): ref is { schema: string; table: string } => !!ref.schema && !!ref.table);
        this.remarks = structure?.remarks?.trim() || null;
        this.viewLineage = new Map((structure?.viewColumns || [])
          .map(column => [column.name.toLowerCase(), column]));
      });
  }

  /** Split a lineage source string for display and linking. */
  sourceParts(source: string): { schema: string | null; table: string | null; column: string } {
    return parseColumnRef(source);
  }

  lineageFor(columnName: string): ViewColumnMeta | null {
    return this.viewLineage.get(columnName.toLowerCase()) ?? null;
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
