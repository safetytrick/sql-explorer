import {Injectable} from '@angular/core';
import {BehaviorSubject} from 'rxjs';
import {QueryFacets, TableMode, TagMode} from './query-search.service';

/** Normalized "schema.table" key, lower-cased to line up with the backend's facet keys. */
export function tableKey(schema: string, table: string): string {
  return (schema + '.' + table).toLowerCase();
}

export interface QueryScope {
  schemas: string[];   // lower-cased normalized schema names
  tables: string[];    // lower-cased "schema.table" keys
  tableMode: TableMode;
  /** Left out: a query touching any of these is not in the search (sqlx-fph). */
  excludeSchemas: string[];
  excludeTables: string[];
}

/** Schema-tree tag filters that also scope the queries workbench. */
export interface SchemaTagFilter {
  includeTags: string[];
  excludeTags: string[];
  tagMode: TagMode;
}

/**
 * Resolved schema names from {@link SchemaTagFilter} against the current tag map.
 * `includedSchemas` is null when include tags are inactive (no constraint);
 * an empty array means include tags are set but no schemas match.
 */
export interface SchemaTagScope {
  includedSchemas: string[] | null;
  excludedSchemas: string[];
  /** `schema.table` keys covered by an excluded *table* tag. */
  excludedTables: string[];
}

const EMPTY_FACETS: QueryFacets = { tables: {}, schemas: {} };
const EMPTY_TAG_FILTER: SchemaTagFilter = { includeTags: [], excludeTags: [], tagMode: 'ANY' };
const EMPTY_TAG_SCOPE: SchemaTagScope = { includedSchemas: null, excludedSchemas: [], excludedTables: [] };

/**
 * Shared state linking the schema tree and the queries workbench. The workbench
 * owns the search and publishes match counts (facets); the tree renders those
 * counts and toggles the scope selection, which drives the workbench's next
 * search. `active$` tells the (always-mounted) tree whether the workbench is on
 * screen, so it only switches into filter mode there.
 *
 * Schema tag include/exclude filters live here too: the tree edits them and
 * resolves matching schema names; the workbench applies those as search scope.
 */
@Injectable({ providedIn: 'root' })
export class QueryScopeService {

  readonly active$ = new BehaviorSubject<boolean>(false);
  readonly scope$ = new BehaviorSubject<QueryScope>(emptyScope('ANY'));
  /**
   * Criteria switched off in the workbench, by their key in criteria.ts
   * (sqlx-fph). The tree reads it so a paused schema tag is left out of the
   * tag scope it resolves, as the search leaves out every paused criterion.
   */
  readonly paused$ = new BehaviorSubject<string[]>([]);
  readonly facets$ = new BehaviorSubject<QueryFacets>(EMPTY_FACETS);
  /**
   * The schema tree folded away. The tree sits in the app shell, beside the
   * workbench rather than inside it, so the workbench owns this (it is
   * `var-hide=tree` in its link) and the shell and the tree read it here.
   */
  readonly treeHidden$ = new BehaviorSubject<boolean>(false);
  readonly schemaTagFilter$ = new BehaviorSubject<SchemaTagFilter>(EMPTY_TAG_FILTER);
  readonly schemaTagScope$ = new BehaviorSubject<SchemaTagScope>(EMPTY_TAG_SCOPE);

  get scope(): QueryScope {
    return this.scope$.value;
  }

  get schemaTagFilter(): SchemaTagFilter {
    return this.schemaTagFilter$.value;
  }

  get schemaTagScope(): SchemaTagScope {
    return this.schemaTagScope$.value;
  }

  setActive(active: boolean): void {
    this.active$.next(active);
  }

  setTreeHidden(hidden: boolean): void {
    if (hidden !== this.treeHidden$.value) { this.treeHidden$.next(hidden); }
  }

  publishFacets(facets: QueryFacets | null | undefined): void {
    this.facets$.next(facets ?? EMPTY_FACETS);
  }

  /** Replace the whole selection — seeding from a shared URL. */
  setScope(scope: QueryScope): void {
    this.scope$.next({ schemas: [...scope.schemas], tables: [...scope.tables], tableMode: scope.tableMode,
      excludeSchemas: [...scope.excludeSchemas], excludeTables: [...scope.excludeTables] });
  }

  setPaused(keys: string[]): void {
    if (keys.join(',') !== this.paused$.value.join(',')) { this.paused$.next([...keys]); }
  }

  /**
   * The tree's box for a table: off → include → exclude → off (sqlx-fph).
   * `straightToExclude` (alt-click) excludes from off or include, and an
   * excluded table it clears.
   */
  cycleTable(key: string, straightToExclude = false): void {
    const scope = this.scope;
    const next = cycle(scope.tables, scope.excludeTables, key, straightToExclude);
    this.scope$.next({ ...scope, tables: next.include, excludeTables: next.exclude });
  }

  cycleSchema(name: string, straightToExclude = false): void {
    const scope = this.scope;
    const next = cycle(scope.schemas, scope.excludeSchemas, name.toLowerCase(), straightToExclude);
    this.scope$.next({ ...scope, schemas: next.include, excludeSchemas: next.exclude });
  }

  isTableExcluded(key: string): boolean {
    return this.scope.excludeTables.includes(key);
  }

  isSchemaExcluded(name: string): boolean {
    return this.scope.excludeSchemas.includes(name.toLowerCase());
  }

  toggleTable(key: string): void {
    const scope = this.scope;
    this.scope$.next({ ...scope, tables: toggle(scope.tables, key) });
  }

  toggleSchema(name: string): void {
    const scope = this.scope;
    this.scope$.next({ ...scope, schemas: toggle(scope.schemas, name.toLowerCase()) });
  }

  removeTable(key: string): void {
    const scope = this.scope;
    this.scope$.next({ ...scope, tables: scope.tables.filter(t => t !== key),
      excludeTables: scope.excludeTables.filter(t => t !== key) });
  }

  removeSchema(key: string): void {
    const scope = this.scope;
    this.scope$.next({ ...scope, schemas: scope.schemas.filter(s => s !== key),
      excludeSchemas: scope.excludeSchemas.filter(s => s !== key) });
  }

  setTableMode(mode: TableMode): void {
    this.scope$.next({ ...this.scope, tableMode: mode });
  }

  clear(): void {
    this.scope$.next(emptyScope(this.scope.tableMode));
  }

  isTableSelected(key: string): boolean {
    return this.scope.tables.includes(key);
  }

  isSchemaSelected(name: string): boolean {
    return this.scope.schemas.includes(name.toLowerCase());
  }

  setSchemaTagFilter(filter: SchemaTagFilter): void {
    this.schemaTagFilter$.next({
      includeTags: [...filter.includeTags],
      excludeTags: [...filter.excludeTags],
      tagMode: filter.tagMode
    });
  }

  /**
   * Publish the schema names that currently match the tag filter. Call whenever
   * the filter or the underlying schema→tags map changes.
   */
  publishSchemaTagScope(scope: SchemaTagScope): void {
    this.schemaTagScope$.next({
      includedSchemas: scope.includedSchemas === null ? null : [...scope.includedSchemas],
      excludedSchemas: [...scope.excludedSchemas],
      excludedTables: [...scope.excludedTables]
    });
  }

  clearSchemaTagFilter(): void {
    this.schemaTagFilter$.next(EMPTY_TAG_FILTER);
    this.schemaTagScope$.next(EMPTY_TAG_SCOPE);
  }
}

/** A scope selecting nothing and leaving nothing out. */
export function emptyScope(tableMode: TableMode): QueryScope {
  return { schemas: [], tables: [], tableMode, excludeSchemas: [], excludeTables: [] };
}

/** One step of the tree's three-state box over an include list and an exclude list. */
function cycle(include: string[], exclude: string[], value: string, straightToExclude: boolean):
  { include: string[]; exclude: string[] } {
  const included = include.includes(value);
  const excluded = exclude.includes(value);
  const drop = (values: string[]) => values.filter(v => v !== value);
  if (excluded) { return { include: drop(include), exclude: drop(exclude) }; }
  if (included || straightToExclude) { return { include: drop(include), exclude: [...exclude, value] }; }
  return { include: [...include, value], exclude };
}

function toggle(values: string[], value: string): string[] {
  return values.includes(value) ? values.filter(v => v !== value) : [...values, value];
}
