import {Component, OnDestroy, OnInit} from '@angular/core';
import {ActivatedRoute, Router, RouterLink} from '@angular/router';
import {DatePipe, DecimalPipe, SlicePipe} from '@angular/common';
import {FormsModule} from '@angular/forms';
import {debounceTime, Subject, Subscription} from 'rxjs';
import {
  Activity,
  Flag,
  QueryHit,
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
import {resolveDateExpr} from '../date-range-selector/date-math';

const PAGE_SIZE = 50;

/** Mirrors the backend's explicit type set; OTHER matches everything outside it. */
export const QUERY_TYPES = ['SELECT', 'INSERT', 'UPDATE', 'DELETE', 'CREATE', 'ALTER', 'DROP', 'SHOW', 'WITH', 'OTHER'];

/** SqlProfileReportService.SLOW_THRESHOLD / CHATTY_THRESHOLD, in ms. */
const SLOW_MIN_MS = 1000;
const CHATTY_MAX_MS = 20;

interface RangeState { min: number | null; max: number | null; }

/** Component fields holding a tri-state Flag, toggled generically from the template. */
type FlagKey = 'fullScan' | 'tmpTable' | 'tmpDisk' | 'errors' | 'warnings' | 'truncated' | 'multiSchema';

@Component({
  selector: 'app-queries-workbench',
  templateUrl: './queries-workbench.component.html',
  styleUrls: ['./queries-workbench.component.scss'],
  imports: [FormsModule, RouterLink, DatePipe, DecimalPipe, SlicePipe, Highlight, DateRangeSelectorComponent]
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
  analysisFailed = false;
  cleanAnalysis = false;
  analysisSkipped = false;
  failureKind: 'ANALYZER' | 'PARSE' | null = null;
  sort: SortKey = 'IMPACT';

  // ── results ──
  hits: QueryHit[] = [];
  total = 0;
  loading = false;
  selected: QueryHit | null = null;

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
    // the schema tree drives the schema/table scope; a change there re-runs the search
    this.subscriptions.add(this.queryScopeService.scope$.subscribe(() => this.searchTrigger.next()));
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

  private buildRequest(offset: number): QuerySearchRequest {
    return {
      queryRegex: this.queryRegex.trim() || null,
      avgTimeMs: range(this.avgTime),
      maxTimeMs: range(this.maxTime),
      avgRowsScanned: range(this.avgRows),
      selectivity: this.selectivityPct.min === null && this.selectivityPct.max === null ? null
        : { min: pctToFraction(this.selectivityPct.min), max: pctToFraction(this.selectivityPct.max) },
      rowsSorted: range(this.rowsSorted),
      activity: this.activity === 'ANY' ? null : this.activity,
      types: this.types.length ? this.types : null,
      firstSeen: dates(resolveDateExpr(this.firstAfter), resolveDateExpr(this.firstBefore, true)),
      lastSeen: dates(resolveDateExpr(this.lastAfter), resolveDateExpr(this.lastBefore, true)),
      tags: this.tags.length ? this.tags : null,
      tagMode: this.tagMode,
      untaggedOnly: this.untaggedOnly || null,
      schemas: this.resolvedSchemas(),
      excludeSchemas: this.resolvedExcludeSchemas(),
      tables: this.queryScopeService.scope.tables.length ? this.queryScopeService.scope.tables : null,
      tableMode: this.queryScopeService.scope.tableMode,
      fullScan: flag(this.fullScan),
      tmpTable: flag(this.tmpTable),
      tmpDisk: flag(this.tmpDisk),
      errors: flag(this.errors),
      warnings: flag(this.warnings),
      truncated: flag(this.truncated),
      multiSchema: flag(this.multiSchema),
      analyzerWarnings: this.analyzerWarnings || null,
      analysisFailed: this.analysisFailed || null,
      cleanAnalysis: this.cleanAnalysis || null,
      analysisSkipped: this.analysisSkipped || null,
      failureKind: this.failureKind,
      sort: this.sort,
      limit: PAGE_SIZE,
      offset
    };
  }

  /**
   * Manual schema checkboxes ∩ schema-tag include list.
   * {@code null} = unconstrained; empty array = include tags active but no schemas match.
   */
  private resolvedSchemas(): string[] | null {
    const selected = this.queryScopeService.scope.schemas;
    const tagIncluded = this.queryScopeService.schemaTagScope.includedSchemas;
    if (tagIncluded === null) {
      return selected.length ? selected : null;
    }
    if (!selected.length) {
      return tagIncluded;
    }
    const allowed = new Set(tagIncluded);
    return selected.filter(schema => allowed.has(schema));
  }

  private resolvedExcludeSchemas(): string[] | null {
    const excluded = this.queryScopeService.schemaTagScope.excludedSchemas;
    return excluded.length ? excluded : null;
  }

  private runSearch() {
    this.loading = true;
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

  loadMore() {
    this.querySearchService.search(this.buildRequest(this.hits.length)).subscribe(result => {
      this.hits = [...this.hits, ...result.results];
      this.total = result.total;
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
    this.analyzerWarnings = this.analysisFailed = this.cleanAnalysis = this.analysisSkipped = false;
    this.failureKind = null;
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
    if (this.analyzerWarnings) { chips.push('analyzer: warnings'); }
    if (this.analysisFailed) { chips.push('analyzer: failed'); }
    if (this.cleanAnalysis) { chips.push('analyzer: clean'); }
    if (this.analysisSkipped) { chips.push('analyzer: skipped'); }
    if (this.failureKind) { chips.push('failure: ' + this.failureKind.toLowerCase()); }
    return chips;
  }

  // ── execution flag tri-states (template helpers) ──

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

  // ── URL state ──

  private writeFiltersToUrl() {
    const params: Record<string, string> = {};
    const put = (key: string, value: string | null) => { if (value) { params[key] = value; } };
    const putRange = (key: string, r: RangeState) => {
      if (r.min !== null) { params[key + 'Min'] = String(r.min); }
      if (r.max !== null) { params[key + 'Max'] = String(r.max); }
    };
    put('q', this.queryRegex.trim() || null);
    if (this.activity !== 'ANY') { params['activity'] = this.activity; }
    if (this.types.length) { params['queryTypes'] = this.types.join(','); }
    put('firstAfter', this.firstAfter);
    put('firstBefore', this.firstBefore);
    put('lastAfter', this.lastAfter);
    put('lastBefore', this.lastBefore);
    if (this.tags.length) { params['tags'] = this.tags.join(','); }
    if (this.tagMode !== 'ANY') { params['tagMode'] = this.tagMode; }
    if (this.untaggedOnly) { params['untagged'] = '1'; }
    putRange('avgTime', this.avgTime);
    putRange('maxTime', this.maxTime);
    putRange('avgRows', this.avgRows);
    putRange('sel', this.selectivityPct);
    putRange('sorted', this.rowsSorted);
    for (const [key, value] of Object.entries(
      { fullScan: this.fullScan, tmpTable: this.tmpTable, tmpDisk: this.tmpDisk, errors: this.errors, warnings: this.warnings, truncated: this.truncated, multiSchema: this.multiSchema })) {
      if (value !== 'ANY') { params[key] = value; }
    }
    if (this.analyzerWarnings) { params['analWarn'] = '1'; }
    if (this.analysisFailed) { params['analFail'] = '1'; }
    if (this.cleanAnalysis) { params['analClean'] = '1'; }
    if (this.analysisSkipped) { params['analSkipped'] = '1'; }
    if (this.failureKind) { params['failKind'] = this.failureKind; }
    if (this.sort !== 'IMPACT') { params['sort'] = this.sort; }
    this.router.navigate([], { relativeTo: this.route, queryParams: params, replaceUrl: true });
  }

  private readFiltersFromUrl() {
    const params = this.route.snapshot.queryParamMap;
    const readRange = (key: string): RangeState => ({
      min: params.has(key + 'Min') ? Number(params.get(key + 'Min')) : null,
      max: params.has(key + 'Max') ? Number(params.get(key + 'Max')) : null
    });
    this.queryRegex = params.get('q') || '';
    this.activity = (params.get('activity') as Activity) || 'ANY';
    this.types = params.get('queryTypes')?.split(',').filter(Boolean) || [];
    this.firstAfter = params.get('firstAfter');
    this.firstBefore = params.get('firstBefore');
    this.lastAfter = params.get('lastAfter');
    this.lastBefore = params.get('lastBefore');
    this.tags = params.get('tags')?.split(',').filter(Boolean) || [];
    this.tagMode = (params.get('tagMode') as TagMode) || 'ANY';
    this.untaggedOnly = params.get('untagged') === '1';
    this.avgTime = readRange('avgTime');
    this.maxTime = readRange('maxTime');
    this.avgRows = readRange('avgRows');
    this.selectivityPct = readRange('sel');
    this.rowsSorted = readRange('sorted');
    this.fullScan = (params.get('fullScan') as Flag) || 'ANY';
    this.tmpTable = (params.get('tmpTable') as Flag) || 'ANY';
    this.tmpDisk = (params.get('tmpDisk') as Flag) || 'ANY';
    this.errors = (params.get('errors') as Flag) || 'ANY';
    this.warnings = (params.get('warnings') as Flag) || 'ANY';
    this.truncated = (params.get('truncated') as Flag) || 'ANY';
    this.multiSchema = (params.get('multiSchema') as Flag) || 'ANY';
    this.analyzerWarnings = params.get('analWarn') === '1';
    this.analysisFailed = params.get('analFail') === '1';
    this.cleanAnalysis = params.get('analClean') === '1';
    this.analysisSkipped = params.get('analSkipped') === '1';
    this.failureKind = (params.get('failKind') as 'ANALYZER' | 'PARSE') || null;
    this.sort = (params.get('sort') as SortKey) || 'IMPACT';
    this.searchTrigger.next();
  }
}

function range(state: RangeState): { min: number | null, max: number | null } | null {
  return state.min === null && state.max === null ? null : { min: state.min, max: state.max };
}

function pctToFraction(value: number | null): number | null {
  return value === null ? null : value / 100;
}

function dates(after: string | null, before: string | null) {
  return !after && !before ? null : { after: after || null, before: before || null };
}

function flag(value: Flag): Flag | null {
  return value === 'ANY' ? null : value;
}
