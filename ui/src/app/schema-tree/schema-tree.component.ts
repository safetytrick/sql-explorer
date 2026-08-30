import {Component, OnDestroy, OnInit} from '@angular/core';
import {ActivatedRoute, Router, RouterLink, RouterLinkActive} from '@angular/router';
import {forkJoin, Subscription} from 'rxjs';
import {SchemaLensService, SchemaNode} from '../schema-lens.service';
import {EnvironmentService} from '../environment.service';
import {QueryFacets, TagMode} from '../query-search.service';
import {QueryScopeService, SchemaTagFilter, tableKey} from '../query-scope.service';
import {decodeTagFilter, encodeTagFilter, sameTagFilter, TagFilterState} from '../tag-filter/tag-filter-url';

/**
 * The sidebar's filter shape as the URL codec's. Two names for one idea —
 * `includeTags`/`tagMode` here, `tags`/`mode` there — kept apart because the
 * codec is shared with the list pages, and bridged in exactly one place.
 */
function asFilterState(filter: SchemaTagFilter): TagFilterState {
  return { tags: filter.includeTags, excludeTags: filter.excludeTags, mode: filter.tagMode };
}
import {filterSchemaTree, TypeFilter, inertTags} from './tree-filter';
import {AnalyzedSqlService} from '../analyzed-sql.service';
import {ChipStyle, TagColorsService} from '../tag-colors.service';
import {resolveSchemaTagScope} from '../search-url';

@Component({
  selector: 'app-schema-tree',
  templateUrl: './schema-tree.component.html',
  styleUrls: ['./schema-tree.component.scss'],
  imports: [RouterLink, RouterLinkActive]
})
export class SchemaTreeComponent implements OnInit, OnDestroy {

  schemas: SchemaNode[] = [];
  filter = '';
  loading = true;

  /** Show all objects, only base tables, or only views. */
  typeFilter: TypeFilter = 'all';

  /** In filter mode the tree scopes the queries workbench instead of navigating. */
  active = false;
  private facets: QueryFacets = { tables: {}, schemas: {} };

  /** schema name (lower) → tag names on that schema */
  private tagsBySchema = new Map<string, string[]>();
  /** Known schema-type tag names for suggestions. */
  private schemaTagNames: string[] = [];
  /** tag → the `schema.table` keys it covers; drives table-tag exclusion. */
  private tablesByTag = new Map<string, string[]>();
  /** Known table-type tag names, also offered as exclusion suggestions. */
  private tableTagNames: string[] = [];

  includeInput = '';
  excludeInput = '';
  includeSuggestions: string[] = [];
  excludeSuggestions: string[] = [];
  tagFiltersOpen = false;

  private subscriptions = new Subscription();

  constructor(private schemaLensService: SchemaLensService,
              private environmentService: EnvironmentService,
              private queryScopeService: QueryScopeService,
              private analyzedSqlService: AnalyzedSqlService,
              private tagColorsService: TagColorsService,
              private route: ActivatedRoute,
              private router: Router) { }

  ngOnInit() {
    // the header interceptor scopes the request; reload whenever the selection changes
    this.subscriptions.add(this.environmentService.current$.subscribe(() => this.reload()));
    this.subscriptions.add(this.queryScopeService.active$.subscribe(active => this.active = active));
    this.subscriptions.add(this.queryScopeService.facets$.subscribe(facets => this.facets = facets));
    this.subscriptions.add(this.queryScopeService.scope$.subscribe(() => { /* re-render selection */ }));
    // The URL is the source of truth for this filter, not this component.
    // The header strip on /tables and /schemas writes the same parameter, and
    // the workbench seeds itself from it — so the sidebar has to read it too,
    // or the two controls on those pages show different states (sqlx-zl1).
    this.subscriptions.add(this.route.queryParamMap.subscribe(params => {
      const fromUrl = decodeTagFilter(params);
      if (sameTagFilter(fromUrl, asFilterState(this.queryScopeService.schemaTagFilter))) { return; }
      this.queryScopeService.setSchemaTagFilter({
        includeTags: fromUrl.tags, excludeTags: fromUrl.excludeTags, tagMode: fromUrl.mode
      });
    }));
    this.subscriptions.add(this.queryScopeService.schemaTagFilter$.subscribe(() => {
      // external clear (e.g. workbench "clear all") — keep panel open if filters remain
      if (this.tagFilterCount === 0) {
        this.includeInput = '';
        this.excludeInput = '';
        this.includeSuggestions = [];
        this.excludeSuggestions = [];
      }
      // external set (e.g. the workbench seeding a shared URL) — resolve it;
      // harmless re-publish for tree-local edits
      this.publishTagScope();
    }));
    this.subscriptions.add(this.tagColorsService.getSummaries().subscribe());
  }

  ngOnDestroy() {
    this.subscriptions.unsubscribe();
  }

  get includeTags(): string[] {
    return this.queryScopeService.schemaTagFilter.includeTags;
  }

  get excludeTags(): string[] {
    return this.queryScopeService.schemaTagFilter.excludeTags;
  }

  get tagMode(): TagMode {
    return this.queryScopeService.schemaTagFilter.tagMode;
  }

  private reload() {
    this.loading = true;
    this.schemaLensService.getSchemaTree().subscribe({
      next: tree => {
        this.schemas = tree.schemas;
        this.loadSchemaTags(tree.schemas);
        this.loading = false;
      },
      error: () => this.loading = false
    });
    this.analyzedSqlService.getTagsByType('schema').subscribe(names => {
      this.schemaTagNames = names;
    });
    this.loadTableTags();
  }

  /**
   * tag → the `schema.table` keys it covers. Resolved per tag through the
   * tables-by-tag endpoint rather than by asking for every table's tags:
   * table tags are few, tables are thousands.
   */
  private loadTableTags() {
    this.analyzedSqlService.getTagsByType('table').subscribe(names => {
      this.tableTagNames = names;
      if (!names.length) {
        this.tablesByTag = new Map();
        this.publishTagScope();
        return;
      }
      forkJoin(names.map(name => this.analyzedSqlService.getTablesByTag(name))).subscribe({
        next: perTag => {
          this.tablesByTag = new Map(names.map((name, index) =>
            [name, perTag[index].map(table => tableKey(table.schema, table.name))]));
          this.publishTagScope();
        },
        error: error => console.error('table tag load failed; table tags will not exclude', error)
      });
    });
  }

  private loadSchemaTags(schemas: SchemaNode[]) {
    if (!schemas.length) {
      this.tagsBySchema = new Map();
      this.publishTagScope();
      return;
    }
    const paths = schemas.map(schema => '/schema/' + schema.name);
    this.analyzedSqlService.getTagsForPaths(paths).subscribe({
      next: byPath => {
        const map = new Map<string, string[]>();
        for (const schema of schemas) {
          const tags = byPath['/schema/' + schema.name] ?? [];
          map.set(schema.name.toLowerCase(), tags.map(tag => tag.name));
        }
        this.tagsBySchema = map;
        this.publishTagScope();
      },
      // an empty tag map silently turns include filters into "hide everything"
      // and exclude filters into no-ops — make that state diagnosable
      error: error => console.error('schema tag load failed; tag include/exclude will not work', error)
    });
  }

  visibleSchemas(): SchemaNode[] {
    return filterSchemaTree(this.schemas, {
      needle: this.filter,
      typeFilter: this.typeFilter,
      tagFilter: { includeTags: this.includeTags, excludeTags: this.excludeTags, tagMode: this.tagMode },
      tagsBySchema: this.tagsBySchema
    });
  }

  setTypeFilter(value: TypeFilter) {
    this.typeFilter = value;
  }

  get tagFilterCount(): number {
    return this.includeTags.length + this.excludeTags.length;
  }

  /**
   * Inertness is asymmetric, because the two filters reach different things.
   * Exclusion drops both tagged schemas and tagged tables, so an exclude chip
   * is only dead if the tag marks neither. Include narrows *schemas* only, so
   * a table tag there still does nothing and must keep saying so — the point
   * is never to show a chip that looks active but cannot be.
   */
  isInertTag(name: string): boolean {
    return this.isInertExcludeTag(name) || (this.includeTags.includes(name) && this.isInertIncludeTag(name));
  }

  private isInertIncludeTag(name: string): boolean {
    return inertTags([name], { tagsBySchema: this.tagsBySchema }).has(name);
  }

  private isInertExcludeTag(name: string): boolean {
    return this.excludeTags.includes(name)
      && inertTags([name], { tagsBySchema: this.tagsBySchema, tablesByTag: this.tablesByTag }).has(name);
  }

  /** Why a chip is inert, shown on hover. */
  inertReason(name: string): string {
    if (this.excludeTags.includes(name)) {
      return `nothing is tagged "${name}" — no schema and no table carries it, `
        + 'so this exclusion cannot drop anything';
    }
    return `no schema is tagged "${name}" — including narrows schemas only, `
      + 'so a table tag has no effect here (it does work as an exclusion)';
  }

  /** Anything set in the filter panel (tags or type) lights the toggle. */
  get filterPanelActive(): boolean {
    return this.tagFilterCount > 0 || this.typeFilter !== 'all';
  }

  toggleTagFilters() {
    this.tagFiltersOpen = !this.tagFiltersOpen;
  }

  clearTagFilters() {
    this.includeInput = '';
    this.excludeInput = '';
    this.includeSuggestions = [];
    this.excludeSuggestions = [];
    this.queryScopeService.clearSchemaTagFilter();
    this.writeTagFilterToUrl({ includeTags: [], excludeTags: [], tagMode: 'ANY' });
  }

  private updateTagFilter(patch: Partial<{ includeTags: string[]; excludeTags: string[]; tagMode: TagMode }>) {
    const current = this.queryScopeService.schemaTagFilter;
    const next = {
      includeTags: patch.includeTags ?? current.includeTags,
      excludeTags: patch.excludeTags ?? current.excludeTags,
      tagMode: patch.tagMode ?? current.tagMode
    };
    this.queryScopeService.setSchemaTagFilter(next);
    this.writeTagFilterToUrl(next);
    // the schemaTagFilter$ subscription in ngOnInit resolves + publishes
  }

  /**
   * Put the filter in the URL, which is what makes it act on the page.
   *
   * <p>Before this the sidebar wrote only to {@link QueryScopeService}, whose
   * sole consumer is the queries workbench. On /tables and /schemas that made
   * the exclude control accept input, show a chip, filter the sidebar's own
   * tree — and change nothing about the page or the URL. Confirming feedback
   * for a no-op, which is worse than a control that plainly does not work
   * (sqlx-zl1).
   *
   * <p>Skipped when the URL already says this. The workbench also writes this
   * parameter from its own subscription to `schemaTagFilter$`, and
   * `setSchemaTagFilter` emits unconditionally, so writing on every emission
   * would bounce between the two writers.
   */
  private writeTagFilterToUrl(filter: SchemaTagFilter) {
    const wanted = asFilterState(filter);
    if (sameTagFilter(wanted, decodeTagFilter(this.route.snapshot.queryParamMap))) { return; }
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: encodeTagFilter(wanted),
      queryParamsHandling: 'merge',
      replaceUrl: true
    });
  }


  /**
   * Resolve the tag filter to the lists the workbench sends. This used to be a
   * copy of `resolveSchemaTagScope`, kept in step by hand with the graph
   * page's copy; it now calls it, so a shared link and the sidebar cannot
   * disagree about what a tag means.
   */
  private publishTagScope() {
    this.queryScopeService.publishSchemaTagScope(resolveSchemaTagScope(
      this.schemas.map(schema => schema.name.toLowerCase()),
      this.tagsBySchema,
      { includeTags: this.includeTags, excludeTags: this.excludeTags, tagMode: this.tagMode },
      this.tablesByTag));
  }

  // ── include / exclude chips ──

  onIncludeInput(value: string) {
    this.includeInput = value;
    this.includeSuggestions = this.suggestTags(value, this.includeTags, this.excludeTags);
  }

  onExcludeInput(value: string) {
    this.excludeInput = value;
    // exclusion reaches tables as well as schemas, so offer both tag families
    this.excludeSuggestions = this.suggestTags(
      value, this.excludeTags, this.includeTags, [...this.schemaTagNames, ...this.tableTagNames]);
  }

  private suggestTags(text: string, already: string[], other: string[], pool = this.schemaTagNames): string[] {
    const needle = text.trim().toLowerCase();
    if (!needle) {
      return [];
    }
    return [...new Set(pool)]
      .filter(name => !already.includes(name) && !other.includes(name))
      .filter(name => name.toLowerCase().includes(needle))
      .slice(0, 8);
  }

  addIncludeTag(name: string) {
    const value = name.trim();
    if (value && !this.includeTags.includes(value) && !this.excludeTags.includes(value)) {
      this.updateTagFilter({ includeTags: [...this.includeTags, value] });
      this.tagFiltersOpen = true;
    }
    this.includeInput = '';
    this.includeSuggestions = [];
  }

  addExcludeTag(name: string) {
    const value = name.trim();
    if (value && !this.excludeTags.includes(value) && !this.includeTags.includes(value)) {
      this.updateTagFilter({ excludeTags: [...this.excludeTags, value] });
      this.tagFiltersOpen = true;
    }
    this.excludeInput = '';
    this.excludeSuggestions = [];
  }

  removeIncludeTag(name: string) {
    this.updateTagFilter({ includeTags: this.includeTags.filter(tag => tag !== name) });
  }

  removeExcludeTag(name: string) {
    this.updateTagFilter({ excludeTags: this.excludeTags.filter(tag => tag !== name) });
  }

  setTagMode(mode: TagMode) {
    this.updateTagFilter({ tagMode: mode });
  }

  tagStyle(name: string): ChipStyle {
    return this.tagColorsService.chipStyle(name);
  }

  // ── filter mode (queries workbench scope) ──

  tableCount(schema: string, table: string): number {
    return this.facets.tables[tableKey(schema, table)] ?? 0;
  }

  schemaCount(schema: string): number {
    return this.facets.schemas[schema.toLowerCase()] ?? 0;
  }

  /** Once a search has populated facets, zero-match rows dim; before that, nothing dims. */
  private get facetsLoaded(): boolean {
    return Object.keys(this.facets.tables).length > 0;
  }

  isTableDimmed(schema: string, table: string): boolean {
    return this.facetsLoaded
      && this.tableCount(schema, table) === 0
      && !this.isTableSelected(schema, table);
  }

  isTableSelected(schema: string, table: string): boolean {
    return this.queryScopeService.isTableSelected(tableKey(schema, table));
  }

  isSchemaSelected(schema: string): boolean {
    return this.queryScopeService.isSchemaSelected(schema);
  }

  toggleTable(schema: string, table: string) {
    this.queryScopeService.toggleTable(tableKey(schema, table));
  }

  toggleSchema(schema: string) {
    this.queryScopeService.toggleSchema(schema);
  }
}
