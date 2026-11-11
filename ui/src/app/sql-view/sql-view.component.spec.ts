import { COMMON_TEST_PROVIDERS } from '../testing/common-test-providers';
import { ComponentFixture, TestBed, waitForAsync } from '@angular/core/testing';

import { SqlViewComponent } from './sql-view.component';
import { SqlProfile, TableStructure } from '../api-types';
import { HttpTestingController } from '@angular/common/http/testing';

describe('SqlViewComponent', () => {
  let component: SqlViewComponent;
  let fixture: ComponentFixture<SqlViewComponent>;

  beforeEach(waitForAsync(() => {
    TestBed.configureTestingModule({
    imports: [SqlViewComponent],
      providers: [COMMON_TEST_PROVIDERS]
})
    .compileComponents();
  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(SqlViewComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  /**
   * The shape ~5% of the corpus is actually in: the analyzer that fills
   * selectColumns / groupByColumns / schemas never ran or threw, so the wire
   * sends null rather than []. Reading `.length` off those threw during change
   * detection, which aborted the update pass — leaving the failure banner's
   * own interpolation empty, so a failed query showed no reason at all.
   *
   * Modelled on the live `SELECT NOW` profile, digest 0cb5ad1b….
   */
  function profileWithNullColumnArrays(): SqlProfile {
    return {
      id: '0cb5ad1b',
      digest: '0cb5ad1b',
      normalizedDigest: '0cb5ad1b',
      query: 'SELECT\n        NOW',
      type: 'SELECT',
      schemaName: 'ped',
      tables: [],
      criteriaColumns: [],
      joinColumns: [],
      selectColumns: null,
      groupByColumns: null,
      schemas: null,
      mismatchedJoins: [],
      generatedSource: null,
      truncated: false,
      analysisFailed: true,
      analysisSkipped: false,
      analysisFailureReason: 'Failure on: SelectColumnAnalyzer message: missing schema',
      isDml: false,
      isMultiSchema: false,
      runtimeMetrics: null,
      lineNumber: null,
      sourceFileName: null,
      sourcedFiles: [],
      queryActivityStatus: 'ACTIVE',
      modifiedForAnalysis: false,
      repeatedBlocks: null,
      mergedChildren: null,
      perfDigests: null
    } as SqlProfile;
  }

  it('renders a profile whose column arrays are null instead of crashing', () => {
    component.query = profileWithNullColumnArrays();
    component.loading = false;

    expect(() => fixture.detectChanges()).not.toThrow();
  });

  it('shows the analysis failure reason for such a profile', () => {
    component.query = profileWithNullColumnArrays();
    component.loading = false;
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Failure on: SelectColumnAnalyzer message: missing schema');
  });

  it('treats null column arrays as empty when collecting constraining columns', () => {
    component.query = profileWithNullColumnArrays();
    component.loading = false;

    // computeIndexPanels() spreads the same arrays; it must not throw either
    expect(() => fixture.detectChanges()).not.toThrow();
    expect(component.indexPanels).toEqual([]);
  });

  // an index on the ORDER BY column lets MySQL read rows in order and skip the sort
  it('counts ORDER BY columns as columns an index can serve', () => {
    component.query = {...profileWithNullColumnArrays(), query: 'SELECT id FROM account ORDER BY name',
      schemaName: 'app_main', tables: ['app_main.account'], orderByColumns: ['app_main.account.name']} as SqlProfile;
    component.tables = [{
      schema: 'app_main', name: 'account', type: 'TABLE', remarks: '', foreignKeys: [], columns: [],
      underlyingTables: null, viewColumns: null,
      indices: [{name: 'idx_name', columns: ['name'], unique: false, schema: 'app_main', identifier: 'idx_name',
        indexType: 'BTREE', direction: 'A', cardinality: 1, pages: 1, filterCondition: ''}]
    }];
    (component as unknown as {refreshPanels(): void}).refreshPanels();

    expect(component.indexPanels.length).toBe(1);
    expect(component.indexPanels[0].indexes[0].index.name).toBe('idx_name');
    expect(component.indexPanels[0].indexes[0].leading).toBeTrue();
  });

  /**
   * The backend sends a zone-less LocalDateTime, which reaches a template as
   * 26 characters of raw ISO with microseconds. Every other surface formats
   * it; this page did not, because the format was a private field on
   * SqlSummaryComponent rather than something shared.
   */
  describe('first and last seen', () => {

    function withSeen(): SqlProfile {
      const profile = profileWithNullColumnArrays();
      return {
        ...profile,
        runtimeMetrics: {
          firstSeen: '2026-05-04T15:49:26.782619',
          lastSeen: '2026-08-29T07:03:11.004821'
        }
      } as SqlProfile;
    }

    function rendered(): string {
      return (fixture.nativeElement as HTMLElement).textContent || '';
    }

    beforeEach(() => {
      component.query = withSeen();
      component.loading = false;
      fixture.detectChanges();
    });

    it('writes them to the minute, not to the microsecond', () => {
      expect(rendered()).toContain('2026-05-04 15:49');
      expect(rendered()).toContain('2026-08-29 07:03');
    });

    /** The whole defect: the raw value must not survive to the page. */
    it('never shows the raw ISO value', () => {
      expect(rendered()).not.toContain('782619');
      expect(rendered()).not.toContain('15:49:26');
      expect(rendered()).not.toContain('T15:49');
    });

    /** Fixed width is the point — 16 characters in May and in December alike. */
    it('uses the one shared format', () => {
      expect(component.SEEN_FORMAT).toBe('yyyy-MM-dd HH:mm');
    });
  });
  /**
   * sqlx-4vp and sqlx-l9h: the page hands the highlighter the structures it
   * loaded for the index card, and the index card wears the SQL's colours.
   */
  describe('table colours', () => {
    let http: HttpTestingController;

    function structure(name: string, columns: string[], indices: [string, string[]][]): TableStructure {
      return {
        schema: 'app_core', name, type: 'TABLE', remarks: '', foreignKeys: [],
        columns: columns.map((columnName, ordinal) => ({columnName, ordinal, typeName: 'INT', type: 'INTEGER'})),
        indices: indices.map(([idx, cols]) => ({name: idx, columns: cols, unique: false})),
        underlyingTables: null, viewColumns: null,
      } as unknown as TableStructure;
    }

    function load(query: string, tables: string[], criteria: string[], structures: TableStructure[]) {
      http = TestBed.inject(HttpTestingController);
      component.getSql('q1');
      http.expectOne(r => r.url.endsWith('analyzed-sql/q1')).flush({
        ...profileWithNullColumnArrays(), query, schemaName: 'app_core', analysisFailed: false,
        tables: tables.map(t => 'app_core.' + t), criteriaColumns: criteria,
      });
      for (const st of structures) {
        http.expectOne(r => r.url.endsWith('metadata/table/app_core/' + st.name)).flush(st);
      }
      fixture.detectChanges();
    }

    const el = () => fixture.nativeElement as HTMLElement;
    const colorOf = (e: Element | null) => [...(e?.classList ?? [])].find(c => /^sqt-c\d$/.test(c));

    it('places an unqualified column using the loaded structures', () => {
      load('SELECT a.id FROM account a JOIN site s ON s.account_id = a.id WHERE region = ?',
        ['account', 'site'], ['app_core.site.region'],
        [structure('account', ['id', 'name'], []), structure('site', ['id', 'account_id', 'region'], [])]);

      const region = [...el().querySelectorAll('pre.sql-display code span')].find(x => x.textContent === 'region');
      const s = [...el().querySelectorAll('pre.sql-display code span.sqt-alias')].find(x => x.textContent === 's');
      expect(colorOf(region!)).toBeDefined();
      expect(colorOf(region!)).toBe(colorOf(s!));
    });

    it('colours a constrained index column like its table in the SQL', () => {
      load('SELECT a.id FROM account a WHERE a.status = ?', ['account'], ['app_core.account.status'],
        [structure('account', ['id', 'status'], [['idx_status', ['status', 'id']]])]);

      const alias = el().querySelector('pre.sql-display code .sqt-alias');
      const indexCol = [...el().querySelectorAll('.idx-col.matched')].find(x => x.textContent === 'status');
      const chip = [...el().querySelectorAll('.rp-criteria')].find(x => x.textContent === 'status');
      const head = el().querySelector('.tbl-name');
      expect(colorOf(alias)).toBeDefined();
      expect(colorOf(indexCol!)).toBe(colorOf(alias));
      expect(colorOf(chip!)).toBe(colorOf(alias));
      expect(colorOf(head)).toBe(colorOf(alias));
    });

    it('names each side of a self-join on the card, and swatches a column both constrain', () => {
      load('SELECT a.id FROM account a JOIN account p ON p.id = a.parent_id WHERE a.id = ?',
        ['account'], ['app_core.account.id', 'app_core.account.parent_id'],
        [structure('account', ['id', 'parent_id'], [['PRIMARY', ['id']], ['idx_parent', ['parent_id']]])]);

      const labels = [...el().querySelectorAll('.inst-label')].map(x => x.textContent);
      expect(labels).toEqual(['a', 'p']);

      const parent = [...el().querySelectorAll('.idx-col.matched')].find(x => x.textContent === 'parent_id');
      const a = el().querySelector('.inst-label');
      expect(colorOf(parent!)).toBe(colorOf(a));          // only `a` constrains parent_id

      const id = [...el().querySelectorAll('.idx-col.matched')].find(x => x.textContent?.startsWith('id'));
      expect(colorOf(id!)).toBeUndefined();               // both sides do: no single colour…
      expect(id!.querySelectorAll('.inst-dot').length).toBe(2);   // …one swatch each
    });

    describe('pointing at a table in the SQL (sqlx-ee3)', () => {
      const over = (target: Element) => { target.dispatchEvent(new MouseEvent('mouseover', {bubbles: true})); fixture.detectChanges(); };
      const sqlSpan = (cls: string, text: string) =>
        [...el().querySelectorAll(`pre.sql-display code span.${cls}`)].find(x => x.textContent === text)!;
      const panels = () => [...el().querySelectorAll('.idx-panel .tbl-name')].map(x => x.textContent);
      const indexes = () => [...el().querySelectorAll('.idx-name')].map(x => x.textContent);

      beforeEach(() => load(
        'SELECT a.id FROM account a JOIN account p ON p.id = a.parent_id JOIN site s ON s.account_id = a.id WHERE s.region = ?',
        ['account', 'site'], ['app_core.account.id', 'app_core.account.parent_id', 'app_core.site.region', 'app_core.site.account_id'],
        [structure('account', ['id', 'parent_id'], [['PRIMARY', ['id']], ['idx_parent', ['parent_id']]]),
         structure('site', ['id', 'account_id', 'region'], [['idx_region', ['region']], ['idx_account', ['account_id']]])]));

      it('shows every table until something is pointed at', () => {
        expect(panels()).toEqual(['account', 'site']);
      });

      it('narrows to the hovered table, and to the indexes its instance reaches', () => {
        over(sqlSpan('sqt-alias', 'p'));
        expect(panels()).toEqual(['account']);
        expect(indexes()).toEqual(['PRIMARY']);           // p constrains only id

        over(sqlSpan('sqt-alias', 'a'));
        expect(indexes()).toEqual(['idx_parent', 'PRIMARY']);   // a: parent_id and id

        over(sqlSpan('sqt-alias', 's'));
        expect(panels()).toEqual(['site']);
        expect(el().querySelector('.card-head-note .sqt-swatch')?.textContent).toBe('s');
      });

      it('restores the whole pane when the pointer leaves the SQL', () => {
        over(sqlSpan('sqt-alias', 's'));
        el().querySelector('pre.sql-display code')!.dispatchEvent(new MouseEvent('mouseleave'));
        fixture.detectChanges();
        expect(panels()).toEqual(['account', 'site']);
      });
    });
  });
});
