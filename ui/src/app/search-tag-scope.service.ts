import {Injectable} from '@angular/core';
import {catchError, forkJoin, map, Observable, of, switchMap} from 'rxjs';
import {AnnotationService, withSchemaRoles} from './annotation.service';
import {AnalyzedSqlService} from './analyzed-sql.service';
import {SchemaLensService} from './schema-lens.service';
import {tableKey} from './query-scope.service';
import {resolveSchemaTagScope, SearchUrlState, UNCONSTRAINED_TAG_SCOPE} from './search-url';
import type {SchemaTagScope} from './query-scope.service';
import {schemaTarget} from './tag-path';

/**
 * A search's schema-tag filter resolved against live tag data, for the views
 * of the workbench's search that load it themselves: the graph and the
 * server comparison.
 *
 * <p>One resolution for both, deliberately. `?schemaTagsExclude=` travels
 * across navigations (see `scope-url.ts`), so it can arrive on any of them,
 * and it has to mean there exactly what it means on `/queries`, or the same
 * link describes two different working sets.
 *
 * <p>Exclusion reaches tagged tables as well as tagged schemas, which is why
 * the table half is resolved too.
 */
@Injectable({providedIn: 'root'})
export class SearchTagScopeService {

  constructor(private schemaLensService: SchemaLensService,
              private analyzedSqlService: AnalyzedSqlService,
              private annotationService: AnnotationService) {
  }

  resolve(filter: SearchUrlState['schemaTagFilter']): Observable<SchemaTagScope> {
    if (!filter.includeTags.length && !filter.excludeTags.length) {
      return of(UNCONSTRAINED_TAG_SCOPE);
    }
    const tablesByTag$ = filter.excludeTags.length
      ? forkJoin(filter.excludeTags.map(tag => this.analyzedSqlService.getTablesByTag(tag))).pipe(
          map(perTag => new Map<string, string[]>(filter.excludeTags.map((tag, index) =>
            [tag, perTag[index].map(table => tableKey(table.schema, table.name))]))))
      : of(new Map<string, string[]>());
    return this.schemaLensService.getSchemaTree().pipe(switchMap(tree => {
      const names = tree.schemas.map(schema => schema.name.toLowerCase());
      const targets = tree.schemas.map(schema => schemaTarget(schema.name));
      return forkJoin({
        byPath: this.analyzedSqlService.getTagsForTargets(targets),
        tablesByTag: tablesByTag$,
        // a schema role (e.g. `system`) answers the same vocabulary (sqlx-n5j.14)
        roles: this.annotationService.schemaRoles().pipe(catchError(() => of(new Map<string, string[]>())))
      }).pipe(map(({ byPath, tablesByTag, roles }) => {
        // Zipped by index: the batched answer is positional (sqlx-3ai).
        const tagsBySchema = new Map<string, string[]>(tree.schemas.map((schema, index) =>
          [schema.name.toLowerCase(),
            withSchemaRoles(schema.name, (byPath[index] ?? []).map(tag => tag.name), roles)]));
        return resolveSchemaTagScope(names, tagsBySchema, filter, tablesByTag);
      }));
    }));
  }
}
