import {ComponentFixture, TestBed, waitForAsync} from '@angular/core/testing';
import {HttpTestingController} from '@angular/common/http/testing';
import {COMMON_TEST_PROVIDERS} from '../testing/common-test-providers';
import {QueryExamplesComponent} from './query-examples.component';
import {QueryExample} from '../api-types';

/**
 * The happy path is trivial and barely tested here. What is tested is the two
 * ways a submission fails to mean what the person submitting it assumed, both
 * of which follow from the endpoint deriving the digest rather than accepting
 * one.
 */
describe('QueryExamplesComponent', () => {

  const DIGEST = 'abc123';

  let fixture: ComponentFixture<QueryExamplesComponent>;
  let component: QueryExamplesComponent;
  let http: HttpTestingController;

  function example(overrides: Partial<QueryExample> = {}): QueryExample {
    return {
      id: 'e1',
      queryText: 'select id from orders where total > 100',
      normalizedDigest: DIGEST,
      sessionSchema: 'app',
      schemaInferred: false,
      description: null,
      environmentName: 'local',
      createdOn: '2026-08-28T12:00:00Z',
      matchedAProfile: true,
      ...overrides
    };
  }

  /** Open the panel with a given starting list of examples. */
  function openWith(existing: QueryExample[]): void {
    http.expectOne(request => request.url.endsWith('query-examples/' + DIGEST)).flush(existing);
    fixture.detectChanges();
  }

  function submit(statement: string): void {
    component.open = true;
    component.draft = statement;
    component.submit();
  }

  function rendered(): string {
    return (fixture.nativeElement as HTMLElement).textContent || '';
  }

  beforeEach(waitForAsync(() => {
    TestBed.configureTestingModule({
      imports: [QueryExamplesComponent],
      providers: [COMMON_TEST_PROVIDERS]
    }).compileComponents();
  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(QueryExamplesComponent);
    component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
    fixture.componentRef.setInput('digest', DIGEST);
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  /**
   * Asserted on the rendered element rather than its text: the statement is
   * handed to the `highlight` directive, which fills the block in
   * asynchronously, so the text is not in the DOM synchronously. The SQL card
   * above renders the same way for the same reason.
   */
  it('lists the examples it is given', () => {
    openWith([example({queryText: 'select 1'})]);

    expect(component.examples).toHaveSize(1);
    expect((fixture.nativeElement as HTMLElement).querySelectorAll('.example')).toHaveSize(1);
    expect((fixture.nativeElement as HTMLElement).querySelector('pre.sql-display')).toBeTruthy();
  });

  it('says why an empty list matters rather than just being empty', () => {
    openWith([]);

    // the reason there are no examples is the reason you want one
    expect(rendered()).toContain('cannot be planned');
  });

  // ── failure path 1: the paste belongs to a different query ──

  describe('when the submitted statement normalizes to a different query', () => {

    beforeEach(() => {
      openWith([]);
      submit('select something entirely else');
      http.expectOne(request => request.method === 'POST' && request.url.endsWith('query-examples'))
        .flush(example({id: 'e9', normalizedDigest: 'a-different-digest'}));
      fixture.detectChanges();
    });

    /**
     * The important one. Adding it to this page's list would be filing it under
     * a query it is not an example of — precisely the mis-association the
     * derive-don't-accept rule exists to prevent.
     */
    it('does not add it to this query\'s list', () => {
      expect(component.examples).toHaveSize(0);
    });

    it('says so, rather than silently succeeding or silently failing', () => {
      expect(rendered()).toContain('belongs to a different query');
    });

    it('links to the query it does belong to', () => {
      const link = (fixture.nativeElement as HTMLElement).querySelector('a[href*="a-different-digest"]');

      expect(link).toBeTruthy();
    });
  });

  // ── failure path 2: the schema is ambiguous ──

  describe('when the server reports an ambiguous schema', () => {

    beforeEach(() => {
      openWith([]);
      submit('select id from orders');
      http.expectOne(request => request.method === 'POST' && request.url.endsWith('query-examples'))
        .flush({
          reason: 'AMBIGUOUS_SCHEMA',
          detail: 'more than one schema contains the tables this statement references',
          candidateSchemas: ['app', 'archive']
        }, {status: 409, statusText: 'Conflict'});
      fixture.detectChanges();
    });

    /** A closed set of right answers, so the UI offers them rather than a text box. */
    it('offers exactly the schemas the server named', () => {
      expect(component.candidateSchemas).toEqual(['app', 'archive']);
      expect(rendered()).toContain('app');
      expect(rendered()).toContain('archive');
    });

    it('keeps the draft so the statement does not have to be pasted again', () => {
      expect(component.draft).toBe('select id from orders');
    });

    it('resubmits with the chosen schema', () => {
      component.chosenSchema = 'archive';
      component.submit();

      const request = http.expectOne(
        req => req.method === 'POST' && req.url.endsWith('query-examples'));
      expect(request.request.body.sessionSchema).toBe('archive');
      request.flush(example({sessionSchema: 'archive'}));
    });
  });

  /**
   * The submitted body carries the statement and never a digest. If this ever
   * fails, the endpoint's central guarantee has been undone from the UI side.
   */
  it('posts the statement and never a digest', () => {
    openWith([]);
    submit('select id from orders where total > 100');

    const request = http.expectOne(
      req => req.method === 'POST' && req.url.endsWith('query-examples'));
    expect(request.request.body.queryText).toBe('select id from orders where total > 100');
    expect(JSON.stringify(request.request.body)).not.toContain(DIGEST);
    request.flush(example());
  });

  // ── ordering and rendering ──

  /**
   * The server orders by id (a UUIDv7, so chronological) and the component
   * sorts again, because a newly submitted example is spliced in locally
   * without a refetch and that path has no server ordering to inherit.
   */
  it('shows the newest example first, whatever order they arrive in', () => {
    openWith([
      example({id: 'old', queryText: 'select 1', createdOn: '2026-08-01T00:00:00Z'}),
      example({id: 'new', queryText: 'select 2', createdOn: '2026-08-28T00:00:00Z'}),
      example({id: 'mid', queryText: 'select 3', createdOn: '2026-08-14T00:00:00Z'})
    ]);

    expect(component.examples.map(e => e.id)).toEqual(['new', 'mid', 'old']);
  });

  it('keeps a newly added example at the top', () => {
    openWith([example({id: 'old', createdOn: '2026-08-01T00:00:00Z'})]);
    submit('select id from orders where total > 1');
    http.expectOne(request => request.method === 'POST' && request.url.endsWith('query-examples'))
      .flush(example({id: 'fresh', createdOn: '2026-08-28T23:00:00Z'}));
    fixture.detectChanges();

    expect(component.examples.map(e => e.id)).toEqual(['fresh', 'old']);
  });

  /**
   * The panel sits under Runtime metrics in the main column, at the same width
   * as the SQL card, so an example is rendered the same way as the query it is
   * an example of — highlighted, unclamped. It used to live in the narrow rail
   * where a long statement had to be truncated to fit.
   */
  it('renders the statement the way the SQL card above does', () => {
    openWith([example({queryText: 'select id from orders'})]);

    const block = (fixture.nativeElement as HTMLElement).querySelector('pre.sql-display code');
    expect(block).toBeTruthy();
    expect((fixture.nativeElement as HTMLElement).querySelector('.clamped')).toBeNull();
  });

  // ── live explain ──

  it('renders a plan for an example', () => {
    openWith([example()]);
    component.explain(component.examples[0]);

    http.expectOne(req => req.url.endsWith('query-examples/e1/explain'))
      .flush({plan: '{"query_block":{"select_id":1}}', serverName: 'explorer', exampleId: 'e1'});
    fixture.detectChanges();

    expect(rendered()).toContain('explorer');
    expect(rendered()).toContain('query_block');
  });

  /**
   * An environment declining to run EXPLAIN is a policy answer, not a fault, so
   * it reads as one.
   */
  it('explains a 403 as the environment declining rather than as a breakage', () => {
    openWith([example()]);
    component.explain(component.examples[0]);

    http.expectOne(req => req.url.endsWith('query-examples/e1/explain'))
      .flush({reason: 'NOT_ENABLED', detail: 'environment has not enabled EXPLAIN'},
        {status: 403, statusText: 'Forbidden'});
    fixture.detectChanges();

    expect(component.explainErrorFor(component.examples[0])).toContain('does not allow EXPLAIN');
  });
});
