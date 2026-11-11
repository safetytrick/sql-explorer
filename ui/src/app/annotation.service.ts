import {Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {catchError, forkJoin, map, Observable, of} from 'rxjs';
import {schemaTarget, TagTarget} from './tag-path';

const endpoint = '/api/sql-explorer/';

/**
 * A fact recorded about an object, with its provenance (sqlx-n5j.13).
 *
 * Not a tag: a tag is a person's label, present or absent. An annotation says
 * what (namespace, key, value), how sure (confidence), why (basis) and who
 * (a rule or sampler by name, or a person as author). Rows are appended; the
 * server answers with the latest per (target, namespace, key).
 */
export interface Annotation {
  id: number;
  target: TagTarget;
  namespace: string;
  key: string;
  value: string;
  confidence: Confidence;
  basis: string | null;
  sourceKind: 'rule' | 'sampler' | 'person';
  sourceName: string | null;
  author: {email: string | null} | null;
  recordedOn: string | null;
}

export type Confidence = 'inferred' | 'sampled' | 'confirmed';

/** One generator this build recognises, and what the environment has said about it (sqlx-n5j.12). */
export interface GeneratorInfo {
  name: string;
  description: string;
  rules: {id: string; description: string}[];
  /** `used` or `not-used` once a person has said; null until then. */
  status: 'used' | 'not-used' | null;
  confirmation: Annotation | null;
}

/** The namespace and key the `system` schema tag moved to (sqlx-n5j.14). */
export const SCHEMA_ROLE_NAMESPACE = 'schema-role';

@Injectable({providedIn: 'root'})
export class AnnotationService {

  constructor(private http: HttpClient) {}

  /** The current answers on each target, positionally — one list per target, in the order sent. */
  currentFor(targets: TagTarget[]): Observable<Annotation[][]> {
    return this.http.post<Annotation[][]>(endpoint + 'annotations/targets', targets);
  }

  /** The current answer on every target in one namespace. */
  inNamespace(namespace: string): Observable<Annotation[]> {
    return this.http.get<Annotation[]>(endpoint + 'annotations/namespace/' + encodeURIComponent(namespace));
  }

  /** Every row for one (target, namespace, key), newest first. */
  history(target: TagTarget, namespace: string, key: string): Observable<Annotation[]> {
    return this.http.post<Annotation[]>(endpoint + 'annotations/history', {target, namespace, key});
  }

  /** The current person confirms an answer: appended again, confirmed and theirs. */
  confirm(id: number): Observable<Annotation> {
    return this.http.post<Annotation>(endpoint + 'annotation/' + id + '/confirm', null);
  }

  /**
   * Each schema's roles, lower-cased schema name → role values.
   *
   * <p>The `system` schema tag became the `schema-role` annotation, and a
   * role is a term in the same exclusion vocabulary the tag was: a link
   * carrying `schemaTagsExclude=system` still hides MySQL's own schemas. See
   * `withSchemaRoles`.
   */
  schemaRoles(): Observable<Map<string, string[]>> {
    return this.inNamespace(SCHEMA_ROLE_NAMESPACE).pipe(map(rows => {
      const roles = new Map<string, string[]>();
      for (const row of rows ?? []) {
        if (row.target?.type !== 'schema' || row.key !== 'role') { continue; }
        const schema = row.target.schema.toLowerCase();
        roles.set(schema, [...(roles.get(schema) ?? []), row.value]);
      }
      return roles;
    }));
  }

  generators(): Observable<GeneratorInfo[]> {
    return this.http.get<GeneratorInfo[]>(endpoint + 'generators');
  }

  /** A person states whether this environment uses a generator. */
  confirmGenerator(name: string, used: boolean): Observable<Annotation> {
    return this.http.post<Annotation>(endpoint + 'generators/' + encodeURIComponent(name) + '/confirmation', {used});
  }
}

/**
 * A schema's filter terms: its tag names and its roles, together.
 *
 * <p>The scope and tag-filter vocabulary (`schemaTagsExclude`, `tags`) is
 * matched against these. Roles join the tag names here, in one place, so
 * moving `system` from a tag to an annotation changed what it *is* without
 * changing what a link means (sqlx-n5j.14).
 */
export function withSchemaRoles(schema: string, tagNames: string[], roles: Map<string, string[]>): string[] {
  const own = roles.get(schema.toLowerCase()) ?? [];
  return own.length ? [...tagNames, ...own.filter(role => !tagNames.includes(role))] : tagNames;
}

/**
 * Lower-cased schema name → its filter terms (tag names and roles), for a
 * page that lists schemas or their tables. One tag request and one role
 * request, whatever the number of schemas. A role lookup that fails leaves
 * the tag names alone rather than failing the page.
 */
export function schemaTerms(tags: {getTagsForTargets(targets: TagTarget[]): Observable<{name: string}[][]>},
                            annotations: AnnotationService, names: string[]): Observable<Map<string, string[]>> {
  return forkJoin({
    byIndex: tags.getTagsForTargets(names.map(schemaTarget)),
    roles: annotations.schemaRoles().pipe(catchError(() => of(new Map<string, string[]>())))
  }).pipe(map(({byIndex, roles}) => new Map(names.map((name, index) =>
    [name.toLowerCase(), withSchemaRoles(name, (byIndex[index] ?? []).map(tag => tag.name), roles)]))));
}
