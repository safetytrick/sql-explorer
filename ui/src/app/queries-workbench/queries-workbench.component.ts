import {GLOSSARY} from '../glossary';
import {InViewDirective} from '../in-view.directive';
import {Component, OnDestroy, OnInit} from '@angular/core';
import {ActivatedRoute, Router, RouterLink} from '@angular/router';
import {DatePipe, DecimalPipe, SlicePipe} from '@angular/common';
import {FormsModule} from '@angular/forms';
import {debounceTime, Subject, Subscription} from 'rxjs';
import {
  Activity,
  Flag,
  HintAction,
  QueryHit,
  QueryProfile,
  QuerySearchRequest,
  QuerySearchService,
  SortKey,
  TagMode
} from '../query-search.service';
import {AnalyzedSqlService} from '../analyzed-sql.service';
import {EnvironmentService} from '../environment.service';
import {ChipStyle, TagColorsService} from '../tag-colors.service';
import {QueryScopeService} from '../query-scope.service';
import {Highlight} from 'ngx-highlightjs';
import {DateRangeSelectorComponent, DateRangeValue} from '../date-range-selector/date-range-selector.component';
import {buildSearchRequest, decodeSearchParams, encodeSearchParams, RangeState, SearchUrlState} from '../search-url';
import {MergeVariant, mergeVariants, perfDigestsOf, repeatSpread, RepeatSpread} from './merge-group';
import {decodeWorkbenchParams, encodeWorkbenchParams} from './workbench-url';
import {BulkTagBarComponent} from '../bulk-tag/bulk-tag-bar.component';

const PAGE_SIZE = 50;

/** Mirrors the backend's explicit type set; OTHER matches everything outside it. */
export const QUERY_TYPES = ['SELECT', 'INSERT', 'UPDATE', 'DELETE', 'CREATE', 'ALTER', 'DROP', 'SHOW', 'WITH', 'OTHER'];

/** SqlProfileReportService.SLOW_THRESHOLD / CHATTY_THRESHOLD, in ms. */
const SLOW_MIN_MS = 1000;
const CHATTY_MAX_MS = 20;

/** Component fields holding a tri-state Flag, toggled generically from the template. */
type FlagKey = 'fullScan' | 'tmpTable' | 'tmpDisk' | 'errors' | 'warnings' | 'truncated' | 'multiSchema';

@Component({
  selector: 'app-queries-workbench',
  templateUrl: './queries-workbench.component.html',
  styleUrls: ['./queries-workbench.component.scss'],
  imports: [FormsModule, RouterLink, DatePipe, DecimalPipe, SlicePipe, Highlight, DateRangeSelectorComponent,
    BulkTagBarComponent, InViewDirective]
})
export class QueriesWorkbenchComponent implements OnInit, OnDestroy {

  readonly queryTypes = QUERY_TYPES;

  // ── filter state ──
  queryRegex = '';
  activity: Activity = 'ANY';
  types: string[] = [];
  firstAfter: string | null = null;
  firstBefore: string | null = null;
  lastAfter: string | null = null;
  lastBefore: string | null = null;
  tags: string[] = [];
  tagMode: TagMode = 'ANY';
  tagInput = '';
  tagSuggestions: string[] = [];
  untaggedOnly = false;
  avgTime: RangeState = { min: null, max: null };
  maxTime: RangeState = { min: null, max: null };
  avgRows: RangeState = { min: null, max: null };
  selectivityPct: RangeState = { min: null, max: null };
  rowsSorted: RangeState = { min: null, max: null };
  fullScan: Flag = 'ANY';
  tmpTable: Flag = 'ANY';
  tmpDisk: Flag = 'ANY';
  errors: Flag = 'ANY';
  warnings: Flag = 'ANY';
  truncated: Flag = 'ANY';
  multiSchema: Flag = 'ANY';
  analyzerWarnings = false;
  /**
   * Only queries with a stored example. Its own section rather than living
   * under Analyzer: an example says something about what has been *collected*
   * for a query, not about how well the analyzer read it.
   */
  hasExamples = false;
  analysisFailed = false;
  cleanAnalysis = false;
  analysisSkipped = false;
  failureKind: 'ANALYZER' | 'PARSE' | null = null;
  /** Hover definitions (glossary.ts). */
  readonly glossary = GLOSSARY;

  /** Index-hint filter; null = unconstrained (sqlx-yj0.5). */
  hinted: HintAction | null = null;
  readonly hintOptions: { value: HintAction | null; label: string }[] = [
    { value: null, label: 'any' }, { value: 'ANY', label: 'hinted' },
    { value: 'FORCE', label: 'force' }, { value: 'USE', label: 'use' }, { value: 'IGNORE', label: 'ignore' }
  ];
  sort: SortKey = 'IMPACT';
  /**
   * Column drill-down, arriving from a table-lens link. The workbench has no
   * control for it, but it must survive: dropping it here would silently widen
   * a shared search on the first filter change.
   */
  columns: string[] = [];
  /** Index drill-down from a table-lens link; carried for the same reason as `columns`. */
  indexes: string[] = [];

  // ── results ──
  hits: QueryHit[] = [];
  total = 0;
  loading = false;
  selected: QueryHit | null = null;
  mergeGroupOpen = false;
  /**
   * The exceptions to {@link fullSql}, by digest: rows expanded while it is
   * off, rows collapsed while it is on. In the URL as `var-expanded`.
   */
  expanded = new Set<string>();
  /** Every row shows its whole SQL, for reading down the list without clicking (sqlx-ywy.1). `var-sql=full`. */
  fullSql = false;
  /** Digest most recently copied, for the transient "copied" affordance. */
  copied: string | null = null;

  // ── resizable detail panel (bottom half) ──
  detailHeight = 320;
  private resizing = false;
  private resizeStartY = 0;
  private resizeStartHeight = 0;
  private resizeContainer: HTMLElement | null = null;
  private readonly onResizeMove = (event: MouseEvent) => this.resizeMove(event);
  private readonly onResizeEnd = () => this.endResize();

  private readonly searchTrigger = new Subject<void>();
  private subscriptions = new Subscription();

  constructor(private querySearchService: QuerySearchService,
              private analyzedSqlService: AnalyzedSqlService,
              private environmentService: EnvironmentService,
              private tagColorsService: TagColorsService,
              private queryScopeService: QueryScopeService,
              private route: ActivatedRoute,
              private router: Router) { }

  ngOnInit() {
    this.subscriptions.add(this.searchTrigger.pipe(debounceTime(250)).subscribe(() => this.runSearch()));
    this.subscriptions.add(this.environmentService.current$.subscribe(() => this.searchTrigger.next()));
    this.subscriptions.add(this.tagColorsService.getSummaries().subscribe());
    // the schema tree drives the schema/table scope; a change there re-runs
    // the search and (once the URL has seeded us) is reflected back into it
    this.subscriptions.add(this.queryScopeService.scope$.subscribe(() => {
      this.writeFiltersToUrl();
      this.searchTrigger.next();
    }));
    this.subscriptions.add(this.queryScopeService.schemaTagFilter$.subscribe(() => this.writeFiltersToUrl()));
    this.subscriptions.add(this.queryScopeService.schemaTagScope$.subscribe(() => this.searchTrigger.next()));
    this.queryScopeService.setActive(true);
    this.readFiltersFromUrl();
  }

  tagStyle(name: string): ChipStyle {
    return this.tagColorsService.chipStyle(name);
  }

  ngOnDestroy() {
    this.subscriptions.unsubscribe();
    this.queryScopeService.setActive(false);
    this.endResize();
  }

  // ── detail panel resizing ──

  startResize(event: MouseEvent) {
    event.preventDefault();
    this.resizing = true;
    this.resizeStartY = event.clientY;
    this.resizeStartHeight = this.detailHeight;
    this.resizeContainer = (event.target as HTMLElement).closest('.list-and-detail');
    document.addEventListener('mousemove', this.onResizeMove);
    document.addEventListener('mouseup', this.onResizeEnd);
    document.body.style.userSelect = 'none';
    document.body.style.cursor = 'row-resize';
  }

  private resizeMove(event: MouseEvent) {
    if (!this.resizing) { return; }
    const delta = this.resizeStartY - event.clientY;   // drag up grows the panel
    const containerHeight = this.resizeContainer?.clientHeight ?? window.innerHeight;
    const max = Math.max(120, containerHeight - 80);   // always leave room for the list
    this.detailHeight = Math.max(120, Math.min(this.resizeStartHeight + delta, max));
  }

  private endResize() {
    if (!this.resizing) { return; }
    this.resizing = false;
    document.removeEventListener('mousemove', this.onResizeMove);
    document.removeEventListener('mouseup', this.onResizeEnd);
    document.body.style.userSelect = '';
    document.body.style.cursor = '';
  }

  // ── search plumbing ──

  filtersChanged() {
    this.writeFiltersToUrl();
    this.searchTrigger.next();
  }

  onDateRange(which: 'first' | 'last', value: DateRangeValue) {
    if (which === 'first') {
      this.firstAfter = value.from;
      this.firstBefore = value.to;
    } else {
      this.lastAfter = value.from;
      this.lastBefore = value.to;
    }
    this.filtersChanged();
  }

  /** Everything the search is made of, in the shared URL vocabulary. */
  private currentState(): SearchUrlState {
    return {
      queryRegex: this.queryRegex,
      activity: this.activity,
      types: this.types,
      firstAfter: this.firstAfter,
      firstBefore: this.firstBefore,
      lastAfter: this.lastAfter,
      lastBefore: this.lastBefore,
      tags: this.tags,
      tagMode: this.tagMode,
      untaggedOnly: this.untaggedOnly,
      avgTime: this.avgTime,
      maxTime: this.maxTime,
      avgRows: this.avgRows,
      selectivityPct: this.selectivityPct,
      rowsSorted: this.rowsSorted,
      fullScan: this.fullScan,
      tmpTable: this.tmpTable,
      tmpDisk: this.tmpDisk,
      errors: this.errors,
      warnings: this.warnings,
      truncated: this.truncated,
      multiSchema: this.multiSchema,
      analyzerWarnings: this.analyzerWarnings,
      hasExamples: this.hasExamples,
      analysisFailed: this.analysisFailed,
      cleanAnalysis: this.cleanAnalysis,
      analysisSkipped: this.analysisSkipped,
      failureKind: this.failureKind,
      sort: this.sort,
      columns: this.columns,
      indexes: this.indexes,
      hinted: this.hinted,
      scope: this.queryScopeService.scope,
      schemaTagFilter: this.queryScopeService.schemaTagFilter
    };
  }

  private buildRequest(offset: number): QuerySearchRequest {
    return buildSearchRequest(this.currentState(), this.queryScopeService.schemaTagScope,
      { limit: PAGE_SIZE, offset });
  }

  /**
   * Open the graph scoped to these results. A pure URL translation — the
   * graph carries the same search params, so the link is shareable.
   */
  graphResults() {
    this.router.navigate(['/graph'],
      { queryParams: { source: 'search', ...encodeSearchParams(this.currentState()) } });
  }

  /**
   * The search a bulk tag would act on: the current filters with paging
   * stripped, since the action is over the whole match set rather than the
   * page on screen. The server ignores limit/offset here, but sending the
   * page's values would make the request read as though it did not.
   */
  get bulkSearch(): QuerySearchRequest {
    return {...this.buildRequest(0), limit: undefined, offset: undefined};
  }

  /** What the confirmation calls the set, in the same words as the header. */
  get bulkLabel(): string {
    return `${this.total} ${this.total === 1 ? 'query' : 'queries'}`;
  }

  /**
   * Re-run the search after a bulk write.
   *
   * <p>Not a cosmetic refresh: tags are a filter here, so tagging the result
   * set can change what the result set is. Re-running is the only way the page
   * can still be describing itself accurately afterwards.
   */
  reloadAfterBulkTag() {
    this.searchTrigger.next();
  }

  /** Bumped by each new search, so a page requested for the previous one is dropped, not appended. */
  private searchGeneration = 0;
  /** A next page is in flight; scrolling must not ask for it twice. */
  loadingMore = false;

  private runSearch() {
    this.loading = true;
    this.loadingMore = false;
    this.searchGeneration++;
    this.querySearchService.search(this.buildRequest(0)).subscribe({
      next: result => {
        this.hits = result.results;
        this.total = result.total;
        this.queryScopeService.publishFacets(result.facets);
        this.loading = false;
        if (this.selected && !this.hits.some(hit => hit.profile.id === this.selected!.profile.id)) {
          this.selected = null;
        }
        if (!this.selected && this.hits.length) {
          this.selected = this.hits[0];
        }
      },
      error: () => this.loading = false
    });
  }

  /**
   * The next page. Called when the end of the list scrolls near (sqlx-ywy.6)
   * or from the fallback line under it. A no-op while a page is in flight,
   * while the first page is loading, or when everything is already shown.
   */
  loadMore() {
    if (this.loadingMore || this.loading || this.hits.length >= this.total) { return; }
    this.loadingMore = true;
    const generation = this.searchGeneration;
    this.querySearchService.search(this.buildRequest(this.hits.length)).subscribe({
      next: result => {
        if (generation !== this.searchGeneration) { return; }
        this.hits = [...this.hits, ...result.results];
        this.total = result.total;
        this.loadingMore = false;
      },
      error: () => { if (generation === this.searchGeneration) { this.loadingMore = false; } }
    });
  }

  toggleType(type: string) {
    this.types = this.types.includes(type)
      ? this.types.filter(candidate => candidate !== type)
      : [...this.types, type];
    this.filtersChanged();
  }

  clearTypes() {
    this.types = [];
    this.filtersChanged();
  }

  clearAll() {
    this.queryRegex = '';
    this.activity = 'ANY';
    this.types = [];
    this.firstAfter = this.firstBefore = this.lastAfter = this.lastBefore = null;
    this.tags = [];
    this.tagMode = 'ANY';
    this.untaggedOnly = false;
    this.avgTime = { min: null, max: null };
    this.maxTime = { min: null, max: null };
    this.avgRows = { min: null, max: null };
    this.selectivityPct = { min: null, max: null };
    this.rowsSorted = { min: null, max: null };
    this.fullScan = this.tmpTable = this.tmpDisk = this.errors = this.warnings = 'ANY';
    this.truncated = this.multiSchema = 'ANY';
    this.hasExamples = false;
    this.analyzerWarnings = this.analysisFailed = this.cleanAnalysis = this.analysisSkipped = false;
    this.failureKind = null;
    this.hinted = null;
    this.queryScopeService.clear();
    this.queryScopeService.clearSchemaTagFilter();
    this.filtersChanged();
  }

  // ── schema/table scope (driven by the schema tree) ──

  get scopeTables(): string[] {
    return this.queryScopeService.scope.tables;
  }

  get scopeSchemas(): string[] {
    return this.queryScopeService.scope.schemas;
  }

  get tableMode(): 'ANY' | 'ALL' {
    return this.queryScopeService.scope.tableMode;
  }

  set tableMode(mode: 'ANY' | 'ALL') {
    this.queryScopeService.setTableMode(mode);
  }

  removeScopeTable(key: string) {
    this.queryScopeService.removeTable(key);
  }

  removeScopeSchema(key: string) {
    this.queryScopeService.removeSchema(key);
  }

  activeFilterChips(): string[] {
    const chips: string[] = [];
    const rangeChip = (label: string, r: RangeState) => {
      if (r.min !== null || r.max !== null) { chips.push(label); }
    };
    if (this.queryRegex.trim()) { chips.push('sql: /' + this.queryRegex.trim() + '/'); }
    rangeChip('avg time', this.avgTime);
    rangeChip('max time', this.maxTime);
    rangeChip('rows scanned', this.avgRows);
    rangeChip('selectivity', this.selectivityPct);
    rangeChip('rows sorted', this.rowsSorted);
    if (this.activity !== 'ANY') { chips.push('activity: ' + this.activity.toLowerCase()); }
    if (this.types.length) { chips.push('type: ' + this.types.join(', ')); }
    if (this.firstAfter || this.firstBefore) { chips.push('first seen'); }
    if (this.lastAfter || this.lastBefore) { chips.push('last seen'); }
    if (this.tags.length) { chips.push(`tags(${this.tagMode.toLowerCase()}): ${this.tags.join(', ')}`); }
    if (this.untaggedOnly) { chips.push('untagged'); }
    const schemaTagFilter = this.queryScopeService.schemaTagFilter;
    if (schemaTagFilter.includeTags.length) {
      chips.push(`schema tags(${schemaTagFilter.tagMode.toLowerCase()}): ${schemaTagFilter.includeTags.join(', ')}`);
    }
    if (schemaTagFilter.excludeTags.length) {
      chips.push(`exclude schema tags: ${schemaTagFilter.excludeTags.join(', ')}`);
    }
    if (this.fullScan !== 'ANY') { chips.push('full scan: ' + this.fullScan.toLowerCase()); }
    if (this.tmpTable !== 'ANY') { chips.push('tmp table: ' + this.tmpTable.toLowerCase()); }
    if (this.tmpDisk !== 'ANY') { chips.push('tmp disk: ' + this.tmpDisk.toLowerCase()); }
    if (this.errors !== 'ANY') { chips.push('errors: ' + this.errors.toLowerCase()); }
    if (this.warnings !== 'ANY') { chips.push('warnings: ' + this.warnings.toLowerCase()); }
    if (this.truncated !== 'ANY') { chips.push('truncated: ' + this.truncated.toLowerCase()); }
    if (this.multiSchema !== 'ANY') { chips.push('multi-schema: ' + this.multiSchema.toLowerCase()); }
    if (this.hinted) { chips.push(this.hinted === 'ANY' ? 'index hint: any' : 'index hint: ' + this.hinted.toLowerCase()); }
    if (this.analyzerWarnings) { chips.push('analyzer: warnings'); }
    if (this.analysisFailed) { chips.push('analyzer: failed'); }
    if (this.cleanAnalysis) { chips.push('analyzer: clean'); }
    if (this.analysisSkipped) { chips.push('analyzer: skipped'); }
    if (this.failureKind) { chips.push('failure: ' + this.failureKind.toLowerCase()); }
    return chips;
  }

  // ── execution flag tri-states (template helpers) ──

  setHinted(value: HintAction | null) {
    this.hinted = value;
    this.filtersChanged();
  }

  /** The distinct hint actions a row's statement uses, for its badge. */
  hintActions(profile: QueryProfile): string[] {
    return [...new Set((profile.indexHints ?? []).map(hint => hint.action.toLowerCase()))].sort();
  }

  flagValue(key: FlagKey): Flag {
    return this[key];
  }

  setFlag(key: FlagKey, value: Flag) {
    this[key] = value;
    this.filtersChanged();
  }

  // ── slow/chatty shortcuts over the avg-time range ──

  isSlow(): boolean {
    return this.avgTime.min === SLOW_MIN_MS && this.avgTime.max === null;
  }

  isChatty(): boolean {
    return this.avgTime.min === null && this.avgTime.max === CHATTY_MAX_MS;
  }

  toggleSlow() {
    this.avgTime = this.isSlow() ? { min: null, max: null } : { min: SLOW_MIN_MS, max: null };
    this.filtersChanged();
  }

  toggleChatty() {
    this.avgTime = this.isChatty() ? { min: null, max: null } : { min: null, max: CHATTY_MAX_MS };
    this.filtersChanged();
  }

  toggleFailureKind(kind: 'ANALYZER' | 'PARSE') {
    this.failureKind = this.failureKind === kind ? null : kind;
    this.filtersChanged();
  }

  // ── tags ──

  onTagInput() {
    const text = this.tagInput.trim();
    if (!text) {
      this.tagSuggestions = [];
      return;
    }
    this.analyzedSqlService.autoCompleteTags(text).subscribe(names => {
      this.tagSuggestions = names.filter(name => !this.tags.includes(name)).slice(0, 8);
    });
  }

  addTag(name: string) {
    const value = name.trim();
    if (value && !this.tags.includes(value)) {
      this.tags = [...this.tags, value];
      this.filtersChanged();
    }
    this.tagInput = '';
    this.tagSuggestions = [];
  }

  removeTag(name: string) {
    this.tags = this.tags.filter(tag => tag !== name);
    this.filtersChanged();
  }

  // ── selection & formatting ──

  select(hit: QueryHit) {
    this.selected = hit;
  }

  isExpanded(hit: QueryHit): boolean {
    return this.fullSql !== this.expanded.has(hit.profile.digest);
  }

  /** Switch every row between one line and its whole SQL; the per-row exceptions start over. */
  setFullSql(full: boolean) {
    if (full === this.fullSql) { return; }
    this.fullSql = full;
    this.expanded = new Set();
    this.writeFiltersToUrl();
  }

  /**
   * Show or hide a row's whole formatted SQL in place, as the table page lists
   * it. The row's own click selects it, so the toggle must not also do that.
   */
  toggleExpanded(hit: QueryHit, event?: Event) {
    event?.stopPropagation();
    const digest = hit.profile.digest;
    if (!this.expanded.delete(digest)) {
      this.expanded.add(digest);
    }
    this.writeFiltersToUrl();
  }

  sqlVerb(sql: string): string {
    return (sql.trim().split(/\s+/)[0] || '').toUpperCase();
  }

  sqlRest(sql: string): string {
    const trimmed = sql.trim().replace(/\s+/g, ' ');
    return trimmed.slice(trimmed.indexOf(' ') + 1);
  }

  ms(picos: number | null | undefined): string {
    if (picos === null || picos === undefined) { return '—'; }
    const value = picos / 1_000_000_000;
    if (value >= 60000) { return (value / 60000).toFixed(1) + 'm'; }
    if (value >= 1000) { return (value / 1000).toFixed(1) + 's'; }
    return value.toFixed(1) + 'ms';
  }

  /** The merge group behind the selected query, one row per distinct digest. */
  /**
   * The repeat range across a group's members, or null when they agree.
   *
   * <p>Read per row while scanning, so it is computed from what the list
   * already carries — `mergedChildren` and `repeatedBlocks` ride along on every
   * hit — rather than fetched. The members are not split on this: they usually
   * come from one call site, and that is the thing worth fixing (sqlx-6v1).
   */
  spreadOf(profile: QueryProfile): RepeatSpread | null {
    return repeatSpread(profile.repeatedBlocks, profile.mergedChildren);
  }

  /**
   * The MySQL digests behind the selected profile.
   *
   * <p>This pane used to label `profile.digest` as `perf_schema`, which is the
   * normalized digest — `complete()` overwrites the field — so it showed the
   * same string as the `app norm` box beside it, under the wrong name.
   */
  get perfDigests(): string[] {
    return perfDigestsOf(this.selected?.profile);
  }

  get mergeVariants(): MergeVariant[] {
    if (!this.selected) { return []; }
    return mergeVariants(this.selected.profile.mergedChildren, this.selected.profile.repeatedBlocks);
  }

  toggleMergeGroup() {
    this.mergeGroupOpen = !this.mergeGroupOpen;
  }

  /**
   * Copy a digest. Self-contained on purpose — no deep link into Grafana or
   * a job runner, so nothing here depends on per-environment URL config.
   */
  copy(value: string) {
    navigator.clipboard.writeText(value).then(
      () => this.flashCopied(value),
      () => this.copied = null);
  }

  private flashCopied(value: string) {
    this.copied = value;
    setTimeout(() => {
      if (this.copied === value) { this.copied = null; }
    }, 1500);
  }

  rows(value: number | null | undefined): string {
    if (value === null || value === undefined) { return '—'; }
    if (value >= 1_000_000) { return (value / 1_000_000).toFixed(1) + 'M'; }
    if (value >= 1_000) { return Math.round(value / 1_000) + 'K'; }
    return String(value);
  }

  impactOf(hit: QueryHit): string {
    const metrics = hit.profile.runtimeMetrics;
    if (!metrics) { return '—'; }
    return this.ms(metrics.totalTime);
  }

  selectivityOf(hit: QueryHit): string {
    const metrics = hit.profile.runtimeMetrics;
    if (!metrics) { return '—'; }
    const fraction = metrics.averageRowsScanned === 0 ? 1 : metrics.averageRowsSent / metrics.averageRowsScanned;
    return (fraction * 100).toFixed(1) + '%';
  }

  // ── URL state (shared vocabulary: see search-url.ts) ──

  /** Guards against writing the URL before it has been read once. */
  private urlReady = false;

  private writeFiltersToUrl() {
    if (!this.urlReady) { return; }
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: {
        ...encodeSearchParams(this.currentState()),
        ...encodeWorkbenchParams({ expanded: [...this.expanded], fullSql: this.fullSql })
      },
      replaceUrl: true
    });
  }

  private readFiltersFromUrl() {
    const state = decodeSearchParams(this.route.snapshot.queryParamMap);
    this.queryRegex = state.queryRegex;
    this.activity = state.activity;
    this.types = state.types;
    this.firstAfter = state.firstAfter;
    this.firstBefore = state.firstBefore;
    this.lastAfter = state.lastAfter;
    this.lastBefore = state.lastBefore;
    this.tags = state.tags;
    this.tagMode = state.tagMode;
    this.untaggedOnly = state.untaggedOnly;
    this.avgTime = state.avgTime;
    this.maxTime = state.maxTime;
    this.avgRows = state.avgRows;
    this.selectivityPct = state.selectivityPct;
    this.rowsSorted = state.rowsSorted;
    this.fullScan = state.fullScan;
    this.tmpTable = state.tmpTable;
    this.tmpDisk = state.tmpDisk;
    this.errors = state.errors;
    this.warnings = state.warnings;
    this.truncated = state.truncated;
    this.multiSchema = state.multiSchema;
    this.analyzerWarnings = state.analyzerWarnings;
    this.hasExamples = state.hasExamples;
    this.analysisFailed = state.analysisFailed;
    this.cleanAnalysis = state.cleanAnalysis;
    this.analysisSkipped = state.analysisSkipped;
    this.failureKind = state.failureKind;
    this.sort = state.sort;
    this.columns = state.columns;
    this.indexes = state.indexes;
    this.hinted = state.hinted;
    const view = decodeWorkbenchParams(this.route.snapshot.queryParamMap);
    this.expanded = new Set(view.expanded);
    this.fullSql = view.fullSql;
    // seed the schema-tree scope from a shared link; the tree renders from
    // the service, so its checkboxes and tag chips light up automatically
    if (state.scope.schemas.length || state.scope.tables.length || state.scope.tableMode !== 'ANY') {
      this.queryScopeService.setScope(state.scope);
    }
    if (state.schemaTagFilter.includeTags.length || state.schemaTagFilter.excludeTags.length) {
      this.queryScopeService.setSchemaTagFilter(state.schemaTagFilter);
    }
    this.urlReady = true;
    // sync back so in-session tree scope shows up even on a bare /queries URL
    this.writeFiltersToUrl();
    this.searchTrigger.next();
  }
}

function flag(value: Flag): Flag | null {
  return value === 'ANY' ? null : value;
}
