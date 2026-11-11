import {TestBed} from '@angular/core/testing';
import {HttpTestingController} from '@angular/common/http/testing';
import {of} from 'rxjs';
import {COMMON_TEST_PROVIDERS} from './testing/common-test-providers';
import {AnnotationService, schemaTerms, withSchemaRoles} from './annotation.service';
import {matchesScopeTags} from './scope-tags';

/** The `system` tag became a schema-role annotation; the filter vocabulary did not change (sqlx-n5j.14). */
describe('schema roles', () => {

  it('reads each schema\'s roles from the schema-role namespace, lower-cased', () => {
    TestBed.configureTestingModule({providers: [COMMON_TEST_PROVIDERS]});
    const service = TestBed.inject(AnnotationService);
    const http = TestBed.inject(HttpTestingController);
    let roles: Map<string, string[]> | null = null;

    service.schemaRoles().subscribe(answer => roles = answer);
    http.expectOne(r => r.url.endsWith('annotations/namespace/schema-role')).flush([
      {target: {type: 'schema', schema: 'MySQL', table: '', digest: ''}, key: 'role', value: 'system'},
      {target: {type: 'table', schema: 'app', table: 'account', digest: ''}, key: 'role', value: 'ignored'}
    ]);

    expect(roles!.get('mysql')).toEqual(['system']);
    expect(roles!.has('app')).toBeFalse();
  });

  it('adds a schema\'s roles to its tag names, once', () => {
    const roles = new Map([['mysql', ['system']]]);
    expect(withSchemaRoles('MySQL', ['core'], roles)).toEqual(['core', 'system']);
    expect(withSchemaRoles('mysql', ['system'], roles)).toEqual(['system']);
    expect(withSchemaRoles('app', ['core'], roles)).toEqual(['core']);
  });

  it('so a scope excluding system still excludes the schema', () => {
    const terms = withSchemaRoles('mysql', [], new Map([['mysql', ['system']]]));
    expect(matchesScopeTags(terms, {includeTags: [], excludeTags: ['system'], tagMode: 'ANY'})).toBeFalse();
  });

  it('keeps the tag names when the roles cannot be read', () => {
    TestBed.configureTestingModule({providers: [COMMON_TEST_PROVIDERS]});
    const service = TestBed.inject(AnnotationService);
    const http = TestBed.inject(HttpTestingController);
    let terms: Map<string, string[]> | null = null;

    schemaTerms({getTagsForTargets: () => of([[{name: 'core'}]])}, service, ['app']).subscribe(answer => terms = answer);
    http.expectOne(r => r.url.endsWith('annotations/namespace/schema-role')).flush('down', {status: 500, statusText: 'down'});

    expect(terms!.get('app')).toEqual(['core']);
  });
});
