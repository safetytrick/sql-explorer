import { Injectable } from '@angular/core';
import { BehaviorSubject } from 'rxjs';
import { QueryFacets, TableMode } from './query-search.service';

/** Normalized "schema.table" key, lower-cased to line up with the backend's facet keys. */
export function tableKey(schema: string, table: string): string {
  return (schema + '.' + table).toLowerCase();
}

export interface QueryScope {
  schemas: string[];   // lower-cased normalized schema names
  tables: string[];    // lower-cased "schema.table" keys
  tableMode: TableMode;
}

const EMPTY_FACETS: QueryFacets = { tables: {}, schemas: {} };

/**
 * Shared state linking the schema tree and the queries workbench. The workbench
 * owns the search and publishes match counts (facets); the tree renders those
 * counts and toggles the scope selection, which drives the workbench's next
 * search. `active$` tells the (always-mounted) tree whether the workbench is on
 * screen, so it only switches into filter mode there.
 */
@Injectable({ providedIn: 'root' })
export class QueryScopeService {

  readonly active$ = new BehaviorSubject<boolean>(false);
  readonly scope$ = new BehaviorSubject<QueryScope>({ schemas: [], tables: [], tableMode: 'ANY' });
  readonly facets$ = new BehaviorSubject<QueryFacets>(EMPTY_FACETS);

  get scope(): QueryScope {
    return this.scope$.value;
  }

  setActive(active: boolean): void {
    this.active$.next(active);
  }

  publishFacets(facets: QueryFacets | null | undefined): void {
    this.facets$.next(facets ?? EMPTY_FACETS);
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
    this.scope$.next({ ...scope, tables: scope.tables.filter(t => t !== key) });
  }

  removeSchema(key: string): void {
    const scope = this.scope;
    this.scope$.next({ ...scope, schemas: scope.schemas.filter(s => s !== key) });
  }

  setTableMode(mode: TableMode): void {
    this.scope$.next({ ...this.scope, tableMode: mode });
  }

  clear(): void {
    this.scope$.next({ schemas: [], tables: [], tableMode: this.scope.tableMode });
  }

  isTableSelected(key: string): boolean {
    return this.scope.tables.includes(key);
  }

  isSchemaSelected(name: string): boolean {
    return this.scope.schemas.includes(name.toLowerCase());
  }
}

function toggle(values: string[], value: string): string[] {
  return values.includes(value) ? values.filter(v => v !== value) : [...values, value];
}
