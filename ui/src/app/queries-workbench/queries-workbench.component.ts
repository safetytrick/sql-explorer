import {SourceService} from '../source.service';
import {countBadge, CountBadge} from './count-badge';
import {GLOSSARY} from '../glossary';
import {InViewDirective} from '../in-view.directive';
import {Component, OnDestroy, OnInit} from '@angular/core';
import {ActivatedRoute, ParamMap, Router, RouterLink} from '@angular/router';
import {DatePipe, DecimalPipe, NgTemplateOutlet, SlicePipe} from '@angular/common';
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
import {AnnotationService} from '../annotation.service';
import {GeneratorOrigin, generatorOrigin, generatorStatuses, GeneratorStatuses, originLabel, originRules} from '../generator-origin';
import {SqlHighlightDirective} from '../sql-highlight/sql-highlight.directive';
import {DateRangeSelectorComponent, DateRangeValue} from '../date-range-selector/date-range-selector.component';
import {buildSearchRequest, decodeSearchParams, encodeSearchParams, RangeState, SearchUrlState} from '../search-url';
import {addCriterion, Criterion, criteriaOf, negate, removeCriterion, toggleOff} from '../criteria';
import {MergeVariant, mergeVariants, perfDigestsOf, repeatSpread, RepeatSpread} from './merge-group';
import {decodeWorkbenchParams, encodeWorkbenchParams, WorkbenchPanel} from './workbench-url';
import {BulkTagBarComponent} from '../bulk-tag/bulk-tag-bar.component';
import {GraphLensComponent} from '../graph-lens/graph-lens.component';
import {GRAPH_PARAMS, GRAPH_VIEW, VIEW_PARAM} from '../graph-lens/graph-url';
import {COMPARE_PARAMS, SERVERS_VIEW} from '../server-compare/server-compare';
import {ServerCompareComponent} from '../server-compare/server-compare.component';

const PAGE_SIZE = 50;

/** Mirrors the backend's explicit type set; OTHER matches everything outside it. */
export const QUERY_TYPES = ['SELECT', 'INSERT', 'UPDATE', 'DELETE', 'CREATE', 'ALTER', 'DROP', 'SHOW', 'UNKNOWN', 'OTHER'];

/** SqlProfileReportService.SLOW_THRESHOLD / CHATTY_THRESHOLD, in ms. */
/** A one-click bound for a performance range; `on` when the range is exactly this. */
export interface RangePreset { label: string; title: string; min: number | null; max: number | null; }

/**
 * Presets for the performance ranges (sqlx-ywy.3). Free-text bounds are
 * awkward and only occasionally need to be exact, so each range offers a few
 * log-spaced steps; the min/max boxes stay for the exact case. Selectivity is
 * where the steps matter most: the interesting range is near zero, where
 * 0.01% and 0.1% are very different queries.
 */
export const RANGE_PRESETS: Record<'avgTime' | 'maxTime' | 'avgRows' | 'selectivityPct' | 'rowsSorted' | 'executed', RangePreset[]> = {
  avgTime: [
    { label: 'slow >1s', title: 'average execution over a second', min: 1000, max: null },
    { label: 'chatty <20ms', title: 'average execution under 20ms: cheap, so only costly in bulk', min: null, max: 20 },
  ],
  maxTime: [
    { label: '>1s', title: 'slowest execution over a second', min: 1000, max: null },
    { label: '>10s', title: 'slowest execution over ten seconds', min: 10000, max: null },
  ],
  avgRows: [
    { label: '>1k', title: 'scans over a thousand rows per execution', min: 1000, max: null },
    { label: '>100k', title: 'scans over a hundred thousand rows per execution', min: 100000, max: null },
    { label: '>1M', title: 'scans over a million rows per execution', min: 1000000, max: null },
  ],
  selectivityPct: [
    { label: '<0.01%', title: 'sends fewer than 1 row per 10,000 scanned', min: null, max: 0.01 },
    { label: '<0.1%', title: 'sends fewer than 1 row per 1,000 scanned', min: null, max: 0.1 },
    { label: '<1%', title: 'sends fewer than 1 row per 100 scanned', min: null, max: 1 },
    { label: '<10%', title: 'sends fewer than 1 row per 10 scanned', min: null, max: 10 },
  ],
  rowsSorted: [
    { label: '>10k', title: 'sorts over ten thousand rows in total', min: 10000, max: null },
    { label: '>1M', title: 'sorts over a million rows in total', min: 1000000, max: null },
  ],
  executed: [
    { label: 'once', title: 'ran a single time in the captured period', min: null, max: 1 },
    { label: '>1k', title: 'ran over a thousand times', min: 1000, max: null },
    { label: '>1M', title: 'ran over a million times', min: 1000000, max: null },
  ],
};
type RangeKey = keyof typeof RANGE_PRESETS;

/** Component fields holding a tri-state Flag, toggled generically from the template. */
type FlagKey = 'fullScan' | 'tmpTable' | 'tmpDisk' | 'errors' | 'warnings' | 'truncated' | 'multiSchema';

@Component({
  selector: 'app-queries-workbench',
  templateUrl: './queries-workbench.component.html',
  styleUrls: ['./queries-workbench.component.scss'],
  imports: [FormsModule, RouterLink, DatePipe, DecimalPipe, SlicePipe, NgTemplateOutlet, SqlHighlightDirective, DateRangeSelectorComponent,
    BulkTagBarComponent, InViewDirective, GraphLensComponent, ServerCompareComponent]
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
  /** Tags on the tables a query touches (sqlx-aoy.10); `tableTags`. */
  tableTags: string[] = [];
  tableTagInput = '';
  tableTagSuggestions: string[] = [];
  /** Tag names in use on tables, busiest first, for the table-tag suggestions. */
  private tableTagNames: string[] = [];
  /** Exclusions (sqlx-fph): each the twin of an include, edited from the same control. */
  excludeTypes: string[] = [];
  excludeTags: string[] = [];
  excludeTableTags: string[] = [];
  /** Queries left out by hand, by digest. */
  excludeQueries: string[] = [];
  /** Sources the search reads (`sources=`): every figure is theirs alone (sqlx-a9h.1). */
  sources: string[] = [];
  excludeSources: string[] = [];
  /** The environment's loaded sources, offered as the filter's choices. */
  sourceOptions: string[] = [];
  /** Matches per source in the current results. */
  sourceCounts: Record<string, number> = {};
  /** Criteria switched off but kept, by key (criteria.ts): `off=`. */
  off: string[] = [];
  /**
   * The SQL of queries excluded from their row this session, by digest, so
   * their pill can still say what they were once they leave the list. A link
   * opened fresh has only the digest to show.
   */
  private excludedSql = new Map<string, string>();
  avgTime: RangeState = { min: null, max: null };
  maxTime: RangeState = { min: null, max: null };
  avgRows: RangeState = { min: null, max: null };
  selectivityPct: RangeState = { min: null, max: null };
  rowsSorted: RangeState = { min: null, max: null };
  executed: RangeState = { min: null, max: null };
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
  /**
   * Generator filter: a generator among a query's candidates, `none`, or null
   * for unconstrained (sqlx-n5j.12). The options and the row badges come from
   * `/generators`, which also says which ones the environment has confirmed.
   */
  generator: string | null = null;
  generatorOptions: { value: string | null; label: string }[] = [{ value: null, label: 'any' }];
  private generatorStatuses: GeneratorStatuses = new Map();
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
  /**
   * The detail pane under the list. Off by default: it was cramped, did not
   * size itself to its content, and the full query page is what got used
   * instead, so a row click opens that page (sqlx-ywy.5). `var-detail=1`.
   */
  detailPane = false;
  /**
   * The side panels folded away so the results get the page: the schema tree
   * and the filters. `var-hide`; the tree reads its half through
   * {@link QueryScopeService.treeHidden$}, as it lives in the app shell.
   */
  hidden = new Set<WorkbenchPanel>();
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
              private router: Router,
              private annotationService: AnnotationService,
              private sourceService: SourceService) { }

  private loadSourceOptions() {
    this.sourceService.getActiveSources().subscribe({
      next: active => {
        this.sourceOptions = [...new Set((active ?? []).map(imported => imported.source?.sourceRef)
          .filter((ref): ref is string => !!ref))].sort();
      },
      // the filter still works from the link and the facets; only the choices are missing
      error: () => { this.sourceOptions = []; }
    });
  }

  ngOnInit() {
    this.subscriptions.add(this.searchTrigger.pipe(debounceTime(250)).subscribe(() => this.runSearch()));
    this.subscriptions.add(this.environmentService.current$.subscribe(() => {
      this.searchTrigger.next();
      this.loadSourceOptions();
    }));
    this.subscriptions.add(this.annotationService.generators().subscribe({
      next: generators => {
        this.generatorStatuses = generatorStatuses(generators ?? []);
        this.generatorOptions = [{ value: null, label: 'any' },
          ...(generators ?? []).map(generator => ({ value: generator.name, label: generator.name })),
          { value: 'none', label: 'none' }];
      },
      // without the list the filter offers only "any"; a linked generator still applies
      error: () => { /* nothing to offer */ }
    }));
    this.subscriptions.add(this.tagColorsService.getSummaries().subscribe(summaries => {
      this.tableTagNames = summaries.filter(summary => summary.tables > 0)
        .sort((a, b) => b.tables - a.tables || a.name.localeCompare(b.name))
        .map(summary => summary.name);
    }));
    // the schema tree drives the schema/table scope; a change there re-runs
    // the search and (once the URL has seeded us) is reflected back into it
    this.subscriptions.add(this.queryScopeService.scope$.subscribe(() => {
      this.writeFiltersToUrl();
      this.searchTrigger.next();
    }));
    this.subscriptions.add(this.queryScopeService.schemaTagFilter$.subscribe(() => this.writeFiltersToUrl()));
    this.subscriptions.add(this.queryScopeService.schemaTagScope$.subscribe(() => this.searchTrigger.next()));
    this.queryScopeService.setActive(true);
    // the tree folds itself away from its own header; reflect that in the link
    this.subscriptions.add(this.queryScopeService.treeHidden$.subscribe(hidden => {
      if (hidden === this.hidden.has('tree')) { return; }
      this.setHidden('tree', hidden);
    }));
    // Read on arrival, and again whenever the URL changes under us rather than
    // by our own write: the graph links to the queries on an edge or a table,
    // which is this page with other filters (sqlx-aoy.16), and back/forward
    // land here the same way.
    this.subscriptions.add(this.route.queryParamMap.subscribe(params => {
      if (urlKey(params) !== this.writtenKey) { this.readFiltersFromUrl(); }
    }));
  }

  /**
   * What shows the results: the list, the graph (`view=graph`, sqlx-aoy.16),
   * or the comparison across servers (`view=servers`, sqlx-a9h.2).
   */
  view: WorkbenchView = 'list';

  get graphView(): boolean { return this.view === 'graph'; }

  get serversView(): boolean { return this.view === 'servers'; }

  /** What this page last wrote to the URL, by {@link urlKey}, so its own writes are not read back. */
  private writtenKey: string | null = null;

  tagStyle(name: string): ChipStyle {
    return this.tagColorsService.chipStyle(name);
  }

  ngOnDestroy() {
    this.subscriptions.unsubscribe();
    this.queryScopeService.setActive(false);
    this.queryScopeService.setTreeHidden(false);
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

  /** The page's own copy of every non-scope part of a search; the scope lives in the scope service. */
  private assignFields(state: SearchUrlState) {
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
    this.tableTags = state.tableTags;
    this.avgTime = state.avgTime;
    this.maxTime = state.maxTime;
    this.avgRows = state.avgRows;
    this.selectivityPct = state.selectivityPct;
    this.rowsSorted = state.rowsSorted;
    this.executed = state.executed;
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
    this.generator = state.generator;
    this.excludeTypes = state.excludeTypes;
    this.excludeTags = state.excludeTags;
    this.excludeTableTags = state.excludeTableTags;
    this.excludeQueries = state.excludeQueries;
    this.sources = state.sources;
    this.excludeSources = state.excludeSources;
    this.off = state.off;
    this.queryScopeService.setPaused(state.off);
  }

  /**
   * Make `next` the search: every pill action, sidebar exclusion and row
   * exclusion goes through here (sqlx-fph), so they all edit the one state
   * the URL encodes.
   */
  private applyCriteria(next: SearchUrlState) {
    this.assignFields(next);
    this.queryScopeService.setSchemaTagFilter(next.schemaTagFilter);
    this.queryScopeService.setScope(next.scope);
    this.filtersChanged();
  }

  // ── active criteria (sqlx-fph) ──

  /** Every criterion of the search, as the bar above the results shows it. */
  get criteria(): Criterion[] {
    return criteriaOf(this.currentState());
  }

  /** The pills to draw one by one: excluded queries fold into one pill when there are more than two. */
  pillsShown(criteria: Criterion[]): Criterion[] {
    const queries = criteria.filter(c => c.key.startsWith('query:'));
    return queries.length > 2 ? criteria.filter(c => !c.key.startsWith('query:')) : criteria;
  }

  /** The folded pill for many excluded queries, or null. */
  excludedQueryGroup(criteria: Criterion[]): {count: number; on: boolean; keys: string[]} | null {
    const queries = criteria.filter(c => c.key.startsWith('query:'));
    return queries.length > 2
      ? {count: queries.length, on: queries.some(c => c.on), keys: queries.map(c => c.key)}
      : null;
  }

  /** What a pill shows as its value: a query's SQL when it is on the page, its digest otherwise. */
  pillValue(c: Criterion): string {
    if (c.key.startsWith('query:')) {
      const hit = this.hits.find(candidate => candidate.profile.digest === c.value);
      const sql = hit ? `${this.sqlVerb(hit.profile.query)} ${this.sqlRest(hit.profile.query)}` : this.excludedSql.get(c.value);
      return sql ? sql.slice(0, 40) : c.value.slice(0, 12);
    }
    return c.value;
  }

  pillToggle(c: Criterion) {
    this.applyCriteria(toggleOff(this.currentState(), c.key));
  }

  pillNegate(c: Criterion) {
    this.applyCriteria(negate(this.currentState(), c.key));
  }

  pillRemove(c: Criterion) {
    this.applyCriteria(removeCriterion(this.currentState(), c.key));
  }

  /** Switch the folded excluded-queries pill: all of them off, or all back on. */
  toggleQueryGroup(group: {on: boolean; keys: string[]}) {
    let state = this.currentState();
    for (const key of group.keys) {
      if (state.off.includes(key) !== !group.on) { state = toggleOff(state, key); }
    }
    this.applyCriteria(state);
  }

  removeQueryGroup(group: {keys: string[]}) {
    let state = this.currentState();
    for (const key of group.keys) { state = removeCriterion(state, key); }
    this.applyCriteria(state);
  }

  /** Leave one query out of the list and the graph (sqlx-fph). */
  excludeQuery(hit: QueryHit, event: Event) {
    event.stopPropagation();
    this.excludedSql.set(hit.profile.digest, `${this.sqlVerb(hit.profile.query)} ${this.sqlRest(hit.profile.query)}`);
    this.applyCriteria(addCriterion(this.currentState(), 'query', hit.profile.digest, true));
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
      tableTags: this.tableTags,
      excludeTypes: this.excludeTypes,
      excludeTags: this.excludeTags,
      excludeTableTags: this.excludeTableTags,
      excludeQueries: this.excludeQueries,
      sources: this.sources,
      excludeSources: this.excludeSources,
      off: this.off,
      avgTime: this.avgTime,
      maxTime: this.maxTime,
      avgRows: this.avgRows,
      selectivityPct: this.selectivityPct,
      rowsSorted: this.rowsSorted,
      executed: this.executed,
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
      generator: this.generator,
      scope: this.queryScopeService.scope,
      schemaTagFilter: this.queryScopeService.schemaTagFilter
    };
  }

  private buildRequest(offset: number): QuerySearchRequest {
    return buildSearchRequest(this.currentState(), this.queryScopeService.schemaTagScope,
      { limit: PAGE_SIZE, offset });
  }

  /**
   * Show the graph of these results in place of the list, or the list again
   * (sqlx-aoy.16). `view=graph`; the graph reads the same search params, so
   * the link is shareable either way.
   */
  setGraphView(graph: boolean) {
    this.setView(graph ? 'graph' : 'list');
  }

  /** The list, the graph or the server comparison, each a view of the same search. */
  setView(view: WorkbenchView) {
    if (view === this.view) { return; }
    this.view = view;
    this.writeFiltersToUrl();
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
        this.sourceCounts = result.facets.sources ?? {};
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

  /** Include a type, or take it off; an excluded type (flipped on its pill) is taken off too. */
  toggleType(type: string) {
    if (this.excludeTypes.includes(type)) {
      this.excludeTypes = this.excludeTypes.filter(candidate => candidate !== type);
    } else {
      this.types = this.types.includes(type)
        ? this.types.filter(candidate => candidate !== type)
        : [...this.types, type];
    }
    this.filtersChanged();
  }

  /** Include a source, or take it off; an excluded one is taken off too. */
  toggleSource(source: string) {
    if (this.excludeSources.includes(source)) {
      this.excludeSources = this.excludeSources.filter(candidate => candidate !== source);
    } else {
      this.sources = this.sources.includes(source)
        ? this.sources.filter(candidate => candidate !== source)
        : [...this.sources, source];
    }
    this.filtersChanged();
  }

  clearSources() {
    this.sources = [];
    this.excludeSources = [];
    this.filtersChanged();
  }

  /** Every source worth offering: the loaded ones, and any a link names that is not loaded. */
  get sourceChoices(): string[] {
    return [...new Set([...this.sourceOptions, ...this.sources, ...this.excludeSources])].sort();
  }

  /** Only an environment with more than one source has a choice to make. */
  get showSources(): boolean {
    return this.sourceChoices.length > 1 || this.sources.length > 0 || this.excludeSources.length > 0;
  }

  clearTypes() {
    this.types = [];
    this.excludeTypes = [];
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
    this.tableTags = [];
    this.excludeTypes = [];
    this.excludeTags = [];
    this.excludeTableTags = [];
    this.excludeQueries = [];
    this.sources = [];
    this.excludeSources = [];
    this.off = [];
    this.queryScopeService.setPaused([]);
    this.avgTime = { min: null, max: null };
    this.maxTime = { min: null, max: null };
    this.avgRows = { min: null, max: null };
    this.selectivityPct = { min: null, max: null };
    this.rowsSorted = { min: null, max: null };
    this.executed = { min: null, max: null };
    this.fullScan = this.tmpTable = this.tmpDisk = this.errors = this.warnings = 'ANY';
    this.truncated = this.multiSchema = 'ANY';
    this.hasExamples = false;
    this.analyzerWarnings = this.analysisFailed = this.cleanAnalysis = this.analysisSkipped = false;
    this.failureKind = null;
    this.hinted = null;
    this.generator = null;
    this.queryScopeService.clear();
    this.queryScopeService.clearSchemaTagFilter();
    this.filtersChanged();
  }

  // ── schema/table scope (driven by the schema tree) ──

  /** How many tables the scope selects: the any/all choice only means something with two or more. */
  get scopeTableCount(): number {
    return this.queryScopeService.scope.tables.length;
  }

  get tableMode(): 'ANY' | 'ALL' {
    return this.queryScopeService.scope.tableMode;
  }

  set tableMode(mode: 'ANY' | 'ALL') {
    this.queryScopeService.setTableMode(mode);
  }

  // ── execution flag tri-states (template helpers) ──

  setHinted(value: HintAction | null) {
    this.hinted = value;
    this.filtersChanged();
  }

  hasGeneratorOption(value: string): boolean {
    return this.generatorOptions.some(option => option.value === value);
  }

  setGenerator(value: string | null) {
    this.generator = value;
    this.filtersChanged();
  }

  /** A row's generator, as far as the environment's confirmations allow: asserted, or a candidate. */
  originOf(profile: QueryProfile): GeneratorOrigin {
    return generatorOrigin(profile.generatorCandidates, this.generatorStatuses);
  }

  readonly originLabel = originLabel;
  readonly originRules = originRules;

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

  readonly rangePresets = RANGE_PRESETS;

  /** A row's error or warning badge: compact label, exact count and rate on hover. */
  badge(word: 'err' | 'warn', hit: QueryHit): CountBadge {
    const metrics = hit.profile.runtimeMetrics;
    return countBadge(word, (word === 'err' ? metrics?.errors : metrics?.warnings) ?? 0, metrics?.executed ?? 0);
  }

  presetOn(key: RangeKey, preset: RangePreset): boolean {
    return this[key].min === preset.min && this[key].max === preset.max;
  }

  /** Apply a preset, or clear the range when it is the one already applied. */
  togglePreset(key: RangeKey, preset: RangePreset) {
    this[key] = this.presetOn(key, preset) ? { min: null, max: null } : { min: preset.min, max: preset.max };
    this.filtersChanged();
  }

  toggleFailureKind(kind: 'ANALYZER' | 'PARSE') {
    this.failureKind = this.failureKind === kind ? null : kind;
    this.filtersChanged();
  }

  // ── tags ──

  /** A suggestion picked while typing `-…` is added as an exclusion. */
  addSuggestedTag(name: string) {
    this.addTag((signed(this.tagInput).exclude ? '-' : '') + name);
  }

  addSuggestedTableTag(name: string) {
    this.addTableTag((signed(this.tableTagInput).exclude ? '-' : '') + name);
  }

  onTagInput() {
    const text = signed(this.tagInput).value;
    if (!text) {
      this.tagSuggestions = [];
      return;
    }
    this.analyzedSqlService.autoCompleteTags(text).subscribe(names => {
      this.tagSuggestions = names.filter(name => !this.tags.includes(name) && !this.excludeTags.includes(name)).slice(0, 8);
    });
  }

  /** `name` includes, `-name` excludes (sqlx-fph). */
  addTag(name: string) {
    const {value, exclude} = signed(name);
    this.tagInput = '';
    this.tagSuggestions = [];
    if (value) { this.applyCriteria(addCriterion(this.currentState(), 'tag', value, exclude)); }
  }

  removeTag(name: string) {
    this.applyCriteria(removeCriterion(this.currentState(), 'tag:' + name));
  }

  /** The ± on a tag chip: include ↔ exclude. */
  flipTag(name: string) {
    this.applyCriteria(negate(this.currentState(), 'tag:' + name));
  }

  /** Suggest tags that are on tables, from the summaries already loaded; no request per keystroke. */
  onTableTagInput() {
    const text = signed(this.tableTagInput).value.toLowerCase();
    this.tableTagSuggestions = text
      ? this.tableTagNames.filter(name => name.toLowerCase().includes(text) && !this.tableTags.includes(name)
        && !this.excludeTableTags.includes(name)).slice(0, 8)
      : [];
  }

  /** `name` includes, `-name` excludes (sqlx-fph). */
  addTableTag(name: string) {
    const {value, exclude} = signed(name);
    this.tableTagInput = '';
    this.tableTagSuggestions = [];
    if (value) { this.applyCriteria(addCriterion(this.currentState(), 'tableTag', value, exclude)); }
  }

  removeTableTag(name: string) {
    this.applyCriteria(removeCriterion(this.currentState(), 'tableTag:' + name));
  }

  flipTableTag(name: string) {
    this.applyCriteria(negate(this.currentState(), 'tableTag:' + name));
  }

  // ── selection & formatting ──

  select(hit: QueryHit) {
    this.selected = hit;
  }

  // ── folding the side panels away ──

  isHidden(panel: WorkbenchPanel): boolean {
    return this.hidden.has(panel);
  }

  togglePanel(panel: WorkbenchPanel) {
    this.setHidden(panel, !this.hidden.has(panel));
  }

  /** Both panels away, or both back: the results alone, or the whole workbench. */
  get focused(): boolean {
    return this.hidden.size === 2;
  }

  toggleFocus() {
    const hide = !this.focused;
    this.hidden = new Set(hide ? ['tree', 'filters'] : []);
    this.queryScopeService.setTreeHidden(hide);
    this.writeFiltersToUrl();
  }

  private setHidden(panel: WorkbenchPanel, hide: boolean) {
    const next = new Set(this.hidden);
    if (hide) { next.add(panel); } else { next.delete(panel); }
    this.hidden = next;
    if (panel === 'tree') { this.queryScopeService.setTreeHidden(hide); }
    this.writeFiltersToUrl();
  }

  /** What the folded tree is scoping by, for the badge on its rail. */
  scopeCount(criteria: Criterion[]): number {
    return criteria.filter(c => c.on && SCOPE_KINDS.some(kind => c.key.startsWith(kind))).length;
  }

  /** What the folded filters are filtering by, for the badge on their rail. */
  filterCount(criteria: Criterion[]): number {
    return criteria.filter(c => c.on && !SCOPE_KINDS.some(kind => c.key.startsWith(kind))).length;
  }

  toggleDetailPane() {
    this.detailPane = !this.detailPane;
    if (this.detailPane && !this.selected && this.hits.length) {
      this.selected = this.hits[0];
    }
    this.writeFiltersToUrl();
  }

  /**
   * With the pane on, a click selects. Without it, a click opens the query's
   * full page, and cmd/ctrl-click a new tab, as a link would. A click that ends
   * a text selection — copying SQL out of an expanded row — does neither.
   */
  rowClick(hit: QueryHit, event: MouseEvent) {
    if (this.detailPane) {
      this.select(hit);
      return;
    }
    if ((window.getSelection()?.toString() ?? '').length > 0) { return; }
    if (event.metaKey || event.ctrlKey) {
      this.openInNewTab(hit);
    } else {
      this.router.navigate(['/analyzed-sql', hit.profile.digest]);
    }
  }

  /** Middle-click on a row opens its page in a new tab. */
  rowAuxClick(hit: QueryHit, event: MouseEvent) {
    if (event.button !== 1 || this.detailPane) { return; }
    event.preventDefault();
    this.openInNewTab(hit);
  }

  private openInNewTab(hit: QueryHit) {
    // the serializer puts the environment at the front of the path
    const tree = this.router.createUrlTree(['/analyzed-sql', hit.profile.digest]);
    window.open(this.router.serializeUrl(tree), '_blank', 'noopener');
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
    const current = this.route.snapshot.queryParamMap;
    const queryParams: Record<string, string> = {
      ...encodeSearchParams(this.currentState()),
      ...encodeWorkbenchParams({ expanded: [...this.expanded], fullSql: this.fullSql, detailPane: this.detailPane,
        hide: [...this.hidden] })
    };
    if (this.view !== 'list') {
      queryParams[VIEW_PARAM] = this.view === 'graph' ? GRAPH_VIEW : SERVERS_VIEW;
      // the view's own params are its to write; keep them while it shows
      for (const name of this.view === 'graph' ? GRAPH_PARAMS : COMPARE_PARAMS) {
        const value = current.get(name);
        if (value !== null) { queryParams[name] = value; }
      }
    }
    this.writtenKey = urlKey(paramReader(queryParams));
    this.router.navigate([], { relativeTo: this.route, queryParams, replaceUrl: true });
  }

  private readFiltersFromUrl() {
    const state = decodeSearchParams(this.route.snapshot.queryParamMap);
    this.assignFields(state);
    this.view = viewOf(this.route.snapshot.queryParamMap.get(VIEW_PARAM));
    const view = decodeWorkbenchParams(this.route.snapshot.queryParamMap);
    this.expanded = new Set(view.expanded);
    this.fullSql = view.fullSql;
    this.detailPane = view.detailPane;
    this.hidden = new Set(view.hide);
    this.queryScopeService.setTreeHidden(this.hidden.has('tree'));
    // seed the schema-tree scope from a shared link; the tree renders from
    // the service, so its checkboxes and tag chips light up automatically
    if (state.scope.schemas.length || state.scope.tables.length || state.scope.tableMode !== 'ANY'
      || state.scope.excludeSchemas.length || state.scope.excludeTables.length) {
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

/**
 * The part of the URL this page reads: the search, its own view params and
 * which view shows. The graph's `var-` params are left out, so the graph
 * writing them is not mistaken for a new search.
 */
function urlKey(params: Pick<ParamMap, 'get' | 'has'>): string {
  return JSON.stringify([
    encodeSearchParams(decodeSearchParams(params)),
    encodeWorkbenchParams(decodeWorkbenchParams(params)),
    viewOf(params.get(VIEW_PARAM))
  ]);
}

type WorkbenchView = 'list' | 'graph' | 'servers';

function viewOf(param: string | null): WorkbenchView {
  return param === GRAPH_VIEW ? 'graph' : param === SERVERS_VIEW ? 'servers' : 'list';
}

function paramReader(params: Record<string, string>): Pick<ParamMap, 'get' | 'has'> {
  return { get: name => params[name] ?? null, has: name => name in params };
}

/** The criteria the schema tree sets, by key prefix (criteria.ts); the filters panel sets the rest. */
const SCOPE_KINDS = ['schema:', 'table:', 'schemaTag:'];

/** `-name` is an exclusion, `name` an inclusion; either trimmed. */
function signed(raw: string): {value: string; exclude: boolean} {
  const text = raw.trim();
  return text.startsWith('-') ? {value: text.slice(1).trim(), exclude: true} : {value: text, exclude: false};
}

function flag(value: Flag): Flag | null {
  return value === 'ANY' ? null : value;
}
