import {HttpTestingController} from '@angular/common/http/testing';
import {COMMON_TEST_PROVIDERS} from '../testing/common-test-providers';
import {TestBed, waitForAsync} from '@angular/core/testing';

import {UnqueriedTablesComponent} from './unqueried-tables.component';
import {SchemaUsage} from './unqueried-tables';

/**
 * The page has to describe the set it is actually showing. It used to carry
 * the scope in its URL and ignore it, and to decide what a system schema was
 * from a compiled-in list of names (sqlx-g7z).
 */
describe('UnqueriedTablesComponent scope', () => {

  let component: UnqueriedTablesComponent;

  const usage = (schema: string, unqueried: number, system = false): SchemaUsage => ({
    schema, system, described: unqueried + 1,
    unqueried: Array.from({length: unqueried}, (_, i) => ({
      name: 'table' + i, view: false, columnCount: 3
    })),
    dormant: [],
    silent: false
  });

  /** Drives the private scope path the way the router would. */
  const scopeTo = (excludeTags: string[], tags: Record<string, string[]>, showExcluded = false) => {
    const self = component as unknown as {
      scope: {includeTags: string[]; excludeTags: string[]; tagMode: string};
      schemaTags: Map<string, string[]>;
      showExcluded: boolean;
      applyScope(): void;
    };
    self.scope = {includeTags: [], excludeTags, tagMode: 'ANY'};
    self.schemaTags = new Map(Object.entries(tags).map(([k, v]) => [k.toLowerCase(), v]));
    self.showExcluded = showExcluded;
    self.applyScope();
  };

  beforeEach(waitForAsync(() => {
    TestBed.configureTestingModule({
      imports: [UnqueriedTablesComponent],
      providers: [COMMON_TEST_PROVIDERS]
    }).compileComponents();
  }));

  beforeEach(() => {
    component = TestBed.createComponent(UnqueriedTablesComponent).componentInstance;
    component.all = [usage('app', 2), usage('mysql', 40, true), usage('sys', 8, true)];
  });

  it('shows everything when no scope excludes anything', () => {
    scopeTo([], {});

    expect(component.visible.map(u => u.schema)).toEqual(['app', 'mysql', 'sys']);
    expect(component.excludedCount).toBe(0);
  });

  /** The defect: the URL said a scope was active and the page showed everything. */
  it('subtracts the schemas the scope excludes', () => {
    scopeTo(['system'], {mysql: ['system'], sys: ['system']});

    expect(component.visible.map(u => u.schema)).toEqual(['app']);
    expect(component.excludedCount).toBe(2);
  });

  /** Every count on the page describes the set being shown, not the whole tree. */
  it('counts only what is in scope', () => {
    scopeTo(['system'], {mysql: ['system'], sys: ['system']});

    expect(component.counts.schemas).toBe(1);
    expect(component.counts.tables).toBe(2);
  });

  /**
   * This page is the one report where seeing the excluded schemas is sometimes
   * the point, so the toggle is an escape hatch from the scope rather than a
   * filter of its own.
   */
  it('can show past the scope without changing it', () => {
    scopeTo(['system'], {mysql: ['system'], sys: ['system']}, true);

    expect(component.visible.map(u => u.schema)).toEqual(['app', 'mysql', 'sys']);
    expect(component.excludedCount).toBe(2);
  });

  /**
   * The tag is what decides, not the name. A schema MySQL owns but nobody
   * tagged stays in view — which is the whole point of moving the decision off
   * a compiled-in list.
   */
  it('does not exclude an untagged schema just because MySQL owns it', () => {
    scopeTo(['system'], {sys: ['system']});

    expect(component.visible.map(u => u.schema)).toEqual(['app', 'mysql']);
  });

  /**
   * A schema tagged operational by the deployment is excluded like any other,
   * which the old hardcoded list could never express.
   */
  it('excludes a schema MySQL does not own when the deployment tags it', () => {
    scopeTo(['system'], {app: ['system']});

    expect(component.visible.map(u => u.schema)).toEqual(['mysql', 'sys']);
  });

  // rows are tagged in place, from one batched request (sqlx-0d8.4)
  describe('row tags', () => {
    it('asks for every listed table in one request and keys the answers by table', () => {
      const http = TestBed.inject(HttpTestingController);
      (component as unknown as {loadTableTags(): void}).loadTableTags();

      const requests = http.match(r => r.url.endsWith('tags/targets'));
      expect(requests.length).toBe(1);
      expect((requests[0].request.body as unknown[]).length).toBe(50);
      const answers = Array.from({length: 50}, (_, at) => at === 0 ? [{name: 'audit'}] : []);
      requests[0].flush(answers);

      expect(component.rowTags('app', 'table0').map(tag => tag.name)).toEqual(['audit']);
      expect(component.rowTags('app', 'table1')).toEqual([]);
    });

    it('hands a row the same array every time, so a tag just added is not overwritten', () => {
      const http = TestBed.inject(HttpTestingController);
      (component as unknown as {loadTableTags(): void}).loadTableTags();
      http.match(r => r.url.endsWith('tags/targets'))[0].flush(Array.from({length: 50}, () => []));

      expect(component.rowTags('app', 'table1')).toBe(component.rowTags('app', 'table1'));
    });
  });
});
