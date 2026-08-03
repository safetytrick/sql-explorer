import {Component, OnDestroy, OnInit} from '@angular/core';
import {RouterLink, RouterLinkActive} from '@angular/router';
import {Subscription} from 'rxjs';
import {SchemaLensService, SchemaNode} from '../schema-lens.service';
import {EnvironmentService} from '../environment.service';
import {QueryFacets, TagMode} from '../query-search.service';
import {QueryScopeService, tableKey} from '../query-scope.service';
import {AnalyzedSqlService} from '../analyzed-sql.service';
import {ChipStyle, TagColorsService} from '../tag-colors.service';

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
  typeFilter: 'all' | 'tables' | 'views' = 'all';

  /** In filter mode the tree scopes the queries workbench instead of navigating. */
  active = false;
  private facets: QueryFacets = { tables: {}, schemas: {} };

  /** schema name (lower) → tag names on that schema */
  private tagsBySchema = new Map<string, string[]>();
  /** Known schema-type tag names for suggestions. */
  private schemaTagNames: string[] = [];

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
              private tagColorsService: TagColorsService) { }

  ngOnInit() {
    // the header interceptor scopes the request; reload whenever the selection changes
    this.subscriptions.add(this.environmentService.current$.subscribe(() => this.reload()));
    this.subscriptions.add(this.queryScopeService.active$.subscribe(active => this.active = active));
    this.subscriptions.add(this.queryScopeService.facets$.subscribe(facets => this.facets = facets));
    this.subscriptions.add(this.queryScopeService.scope$.subscribe(() => { /* re-render selection */ }));
    this.subscriptions.add(this.queryScopeService.schemaTagFilter$.subscribe(() => {
      // external clear (e.g. workbench "clear all") — keep panel open if filters remain
      if (this.tagFilterCount === 0) {
        this.includeInput = '';
        this.excludeInput = '';
        this.includeSuggestions = [];
        this.excludeSuggestions = [];
      }
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
  }

  private loadSchemaTags(schemas: SchemaNode[]) {
    if (!schemas.length) {
      this.tagsBySchema = new Map();
      this.publishTagScope();
      return;
    }
    const paths = schemas.map(schema => '/schema/' + schema.name);
    this.analyzedSqlService.getTagsForPaths(paths).subscribe(byPath => {
      const map = new Map<string, string[]>();
      for (const schema of schemas) {
        const tags = byPath['/schema/' + schema.name] ?? [];
        map.set(schema.name.toLowerCase(), tags.map(tag => tag.name));
      }
      this.tagsBySchema = map;
      this.publishTagScope();
    });
  }

  visibleSchemas(): SchemaNode[] {
    const needle = this.filter.trim().toLowerCase();
    return this.schemas
      .filter(schema => this.matchesTagFilters(schema.name))
      .map(schema => ({
        name: schema.name,
        tables: schema.tables
          .filter(table => !needle || table.name.toLowerCase().includes(needle))
          .filter(table => this.matchesTypeFilter(table.view))
      }))
      .filter(schema => schema.tables.length > 0);
  }

  private matchesTypeFilter(isView: boolean): boolean {
    switch (this.typeFilter) {
      case 'tables': return !isView;
      case 'views': return isView;
      default: return true;
    }
  }

  setTypeFilter(value: 'all' | 'tables' | 'views') {
    this.typeFilter = value;
  }

  private matchesTagFilters(schemaName: string): boolean {
    const tags = this.tagsBySchema.get(schemaName.toLowerCase()) ?? [];
    if (this.excludeTags.some(tag => tags.includes(tag))) {
      return false;
    }
    if (!this.includeTags.length) {
      return true;
    }
    return this.tagMode === 'ALL'
      ? this.includeTags.every(tag => tags.includes(tag))
      : this.includeTags.some(tag => tags.includes(tag));
  }

  get tagFilterCount(): number {
    return this.includeTags.length + this.excludeTags.length;
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
  }

  private updateTagFilter(patch: Partial<{ includeTags: string[]; excludeTags: string[]; tagMode: TagMode }>) {
    const current = this.queryScopeService.schemaTagFilter;
    this.queryScopeService.setSchemaTagFilter({
      includeTags: patch.includeTags ?? current.includeTags,
      excludeTags: patch.excludeTags ?? current.excludeTags,
      tagMode: patch.tagMode ?? current.tagMode
    });
    this.publishTagScope();
  }

  /** Resolve include/exclude tag filters to schema name lists for the queries workbench. */
  private publishTagScope() {
    const schemaNames = this.schemas.map(schema => schema.name.toLowerCase());
    const excluded = this.excludeTags.length
      ? schemaNames.filter(name => {
          const tags = this.tagsBySchema.get(name) ?? [];
          return this.excludeTags.some(tag => tags.includes(tag));
        })
      : [];

    let included: string[] | null = null;
    if (this.includeTags.length) {
      included = schemaNames.filter(name => {
        if (excluded.includes(name)) {
          return false;
        }
        const tags = this.tagsBySchema.get(name) ?? [];
        return this.tagMode === 'ALL'
          ? this.includeTags.every(tag => tags.includes(tag))
          : this.includeTags.some(tag => tags.includes(tag));
      });
    }

    this.queryScopeService.publishSchemaTagScope({
      includedSchemas: included,
      excludedSchemas: excluded
    });
  }

  // ── include / exclude chips ──

  onIncludeInput(value: string) {
    this.includeInput = value;
    this.includeSuggestions = this.suggestTags(value, this.includeTags, this.excludeTags);
  }

  onExcludeInput(value: string) {
    this.excludeInput = value;
    this.excludeSuggestions = this.suggestTags(value, this.excludeTags, this.includeTags);
  }

  private suggestTags(text: string, already: string[], other: string[]): string[] {
    const needle = text.trim().toLowerCase();
    if (!needle) {
      return [];
    }
    return this.schemaTagNames
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
