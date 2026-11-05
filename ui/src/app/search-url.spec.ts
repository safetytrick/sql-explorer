import {
  buildSearchRequest,
  decodeSearchParams,
  defaultSearchState,
  encodeSearchParams,
  ParamReader,
  resolveSchemaTagScope,
  SearchUrlState,
  UNCONSTRAINED_TAG_SCOPE
} from './search-url';

function reader(params: Record<string, string>): ParamReader {
  return {
    get: (name: string) => params[name] ?? null,
    has: (name: string) => name in params
  };
}

describe('search-url codec', () => {

  it('encodes the default state as an empty param set', () => {
    expect(encodeSearchParams(defaultSearchState())).toEqual({});
  });

  it('keeps the workbench param vocabulary', () => {
    const state = defaultSearchState();
    state.queryRegex = 'select.*member';
    state.types = ['SELECT', 'UPDATE'];
    state.tags = ['hot'];
    state.tagMode = 'ALL';
    state.avgTime = { min: 100, max: null };
    state.fullScan = 'YES';
    state.sort = 'AVG_TIME';
    state.firstAfter = 'now-7d';
    expect(encodeSearchParams(state)).toEqual({
      q: 'select.*member',
      queryTypes: 'SELECT,UPDATE',
      tags: 'hot',
      tagMode: 'ALL',
      avgTimeMin: '100',
      fullScan: 'YES',
      sort: 'AVG_TIME',
      firstAfter: 'now-7d'
    });
  });

  it('encodes the schema-tree scope: schemas, tables, mode, tag filter as intent', () => {
    const state = defaultSearchState();
    state.scope = { schemas: ['tdb'], tables: ['tdb.member_session', 'tdb.branch'], tableMode: 'ALL' };
    state.schemaTagFilter = { includeTags: ['core'], excludeTags: ['legacy'], tagMode: 'ALL' };
    expect(encodeSearchParams(state)).toEqual({
      schemas: 'tdb',
      tables: 'tdb.member_session,tdb.branch',
      tableMode: 'ALL',
      schemaTags: 'core',
      schemaTagsExclude: 'legacy',
      schemaTagMode: 'ALL'
    });
  });

  it('round-trips a fully loaded state', () => {
    const state: SearchUrlState = {
      ...defaultSearchState(),
      queryRegex: 'join',
      activity: 'ACTIVE',
      types: ['SELECT'],
      firstAfter: 'now-30d', firstBefore: 'now',
      lastAfter: null, lastBefore: null,
      tags: ['hot', 'slow'], tagMode: 'ALL', untaggedOnly: false,
      avgTime: { min: 5, max: 500 },
      selectivityPct: { min: null, max: 10 },
      fullScan: 'YES', multiSchema: 'NO',
      analyzerWarnings: true, failureKind: 'PARSE',
      sort: 'LAST_SEEN',
      scope: { schemas: ['tdb', 'app_main'], tables: ['tdb.member_session'], tableMode: 'ALL' },
      schemaTagFilter: { includeTags: ['core'], excludeTags: ['legacy'], tagMode: 'ANY' }
    };
    expect(decodeSearchParams(reader(encodeSearchParams(state)))).toEqual(state);
  });

  it('decodes a bare URL to the default state', () => {
    expect(decodeSearchParams(reader({}))).toEqual(defaultSearchState());
  });
});

describe('resolveSchemaTagScope', () => {

  const tagsBySchema = new Map([
    ['tdb', ['core']],
    ['app_main', ['core', 'legacy']],
    ['other', ['legacy']]
  ]);
  const names = ['tdb', 'app_main', 'other'];

  it('is unconstrained without filters', () => {
    expect(resolveSchemaTagScope(names, tagsBySchema, { includeTags: [], excludeTags: [], tagMode: 'ANY' }))
      .toEqual({ includedSchemas: null, excludedSchemas: [], excludedTables: [] });
  });

  it('resolves include tags by ANY or ALL, exclude wins', () => {
    expect(resolveSchemaTagScope(names, tagsBySchema, { includeTags: ['core'], excludeTags: [], tagMode: 'ANY' }))
      .toEqual({ includedSchemas: ['tdb', 'app_main'], excludedSchemas: [], excludedTables: [] });
    expect(resolveSchemaTagScope(names, tagsBySchema, { includeTags: ['core', 'legacy'], excludeTags: [], tagMode: 'ALL' }))
      .toEqual({ includedSchemas: ['app_main'], excludedSchemas: [], excludedTables: [] });
    expect(resolveSchemaTagScope(names, tagsBySchema, { includeTags: ['core'], excludeTags: ['legacy'], tagMode: 'ANY' }))
      .toEqual({ includedSchemas: ['tdb'], excludedSchemas: ['app_main', 'other'], excludedTables: [] });
  });
});

describe('buildSearchRequest', () => {

  it('applies scope ∩ resolved tag scope', () => {
    const state = defaultSearchState();
    state.scope = { schemas: ['tdb', 'other'], tables: ['tdb.branch'], tableMode: 'ALL' };
    const request = buildSearchRequest(state,
      { includedSchemas: ['tdb', 'app_main'], excludedSchemas: ['other'], excludedTables: [] },
      { limit: null, offset: null });
    expect(request.schemas).toEqual(['tdb']);          // selected ∩ tag-included
    expect(request.excludeSchemas).toEqual(['other']);
    expect(request.tables).toEqual(['tdb.branch']);
    expect(request.tableMode).toBe('ALL');
    expect(request.limit).toBeNull();
  });

  it('widens SELECT to also match CTEs, which are typed WITH by first token', () => {
    const state = defaultSearchState();
    state.types = ['SELECT'];
    const request = buildSearchRequest(state, UNCONSTRAINED_TAG_SCOPE, { limit: 50, offset: 0 });
    expect(request.types).toEqual(['SELECT', 'WITH']);
  });

  it('leaves a WITH-only selection alone, so picking WITH still means only CTEs', () => {
    const state = defaultSearchState();
    state.types = ['WITH'];
    const request = buildSearchRequest(state, UNCONSTRAINED_TAG_SCOPE, { limit: 50, offset: 0 });
    expect(request.types).toEqual(['WITH']);
  });

  it('does not widen a selection that excludes SELECT', () => {
    const state = defaultSearchState();
    state.types = ['INSERT', 'UPDATE'];
    const request = buildSearchRequest(state, UNCONSTRAINED_TAG_SCOPE, { limit: 50, offset: 0 });
    expect(request.types).toEqual(['INSERT', 'UPDATE']);
  });

  it('keeps the other picked types when widening, and does not duplicate WITH', () => {
    const both = defaultSearchState();
    both.types = ['SELECT', 'INSERT'];
    expect(buildSearchRequest(both, UNCONSTRAINED_TAG_SCOPE, { limit: 50, offset: 0 }).types)
      .toEqual(['SELECT', 'INSERT', 'WITH']);

    const already = defaultSearchState();
    already.types = ['SELECT', 'WITH'];
    expect(buildSearchRequest(already, UNCONSTRAINED_TAG_SCOPE, { limit: 50, offset: 0 }).types)
      .toEqual(['SELECT', 'WITH']);
  });

  it('widening never reaches the URL — a shared link says what the user picked', () => {
    const state = defaultSearchState();
    state.types = ['SELECT'];

    // what travels in the link
    const params = encodeSearchParams(state);
    expect(params['queryTypes']).toBe('SELECT');

    // and what comes back out of it
    const decoded = decodeSearchParams(reader(params as Record<string, string>));
    expect(decoded.types).toEqual(['SELECT']);

    // while the request built from that same state still covers CTEs
    expect(buildSearchRequest(decoded, UNCONSTRAINED_TAG_SCOPE, { limit: 50, offset: 0 }).types)
      .toEqual(['SELECT', 'WITH']);
  });

  it('resolves date expressions at build time so shared links stay live', () => {
    const state = defaultSearchState();
    state.firstAfter = 'now-7d';
    const request = buildSearchRequest(state, UNCONSTRAINED_TAG_SCOPE, { limit: 50, offset: 0 });
    expect(request.firstSeen?.after).toMatch(/^\d{4}-\d{2}-\d{2}/);
  });

  it('leaves untouched filters null', () => {
    const request = buildSearchRequest(defaultSearchState(), UNCONSTRAINED_TAG_SCOPE, { limit: 50, offset: 0 });
    expect(request.queryRegex).toBeNull();
    expect(request.schemas).toBeNull();
    expect(request.tables).toBeNull();
    expect(request.fullScan).toBeNull();
    expect(request.firstSeen).toBeNull();
  });
});

describe('two excluded schema tags in the URL', () => {

  const params = (record: Record<string, string>) => ({
    get: (name: string) => (name in record ? record[name] : null),
    has: (name: string) => name in record
  });

  it('survives encode → decode', () => {
    const state = defaultSearchState();
    state.schemaTagFilter = { includeTags: [], excludeTags: ['a', 'b'], tagMode: 'ANY' };

    expect(decodeSearchParams(params(encodeSearchParams(state))).schemaTagFilter.excludeTags)
      .toEqual(['a', 'b']);
  });

  it('survives a real query string, where the comma could have been escaped', () => {
    const state = defaultSearchState();
    state.schemaTagFilter = { includeTags: [], excludeTags: ['a', 'b'], tagMode: 'ANY' };
    const roundTripped = new URLSearchParams(new URLSearchParams(encodeSearchParams(state)).toString());

    const decoded = decodeSearchParams({
      get: (name: string) => roundTripped.get(name),
      has: (name: string) => roundTripped.has(name)
    });
    expect(decoded.schemaTagFilter.excludeTags).toEqual(['a', 'b']);
  });

  it('both reach the request as excluded schemas', () => {
    const scope = resolveSchemaTagScope(
      ['sa', 'sb', 'sc'],
      new Map([['sa', ['a']], ['sb', ['b']], ['sc', ['c']]]),
      { includeTags: [], excludeTags: ['a', 'b'], tagMode: 'ANY' });

    expect(scope.excludedSchemas.sort()).toEqual(['sa', 'sb']);
  });
});

describe('table-tag exclusion', () => {

  const schemaNames = ['sa', 'sb', 'sc'];
  const tagsBySchema = new Map([['sa', ['system']], ['sb', []], ['sc', []]]);
  const tablesByTag = new Map([['clickhouse', ['sb.stat_one', 'sc.stat_two']]]);

  it('resolves a table tag to the tables it covers', () => {
    const scope = resolveSchemaTagScope(schemaNames, tagsBySchema,
      { includeTags: [], excludeTags: ['clickhouse'], tagMode: 'ANY' }, tablesByTag);

    expect(scope.excludedSchemas).toEqual([]);
    expect(scope.excludedTables).toEqual(['sb.stat_one', 'sc.stat_two']);
  });

  it('a schema tag and a table tag excluded together resolve to both lists', () => {
    const scope = resolveSchemaTagScope(schemaNames, tagsBySchema,
      { includeTags: [], excludeTags: ['system', 'clickhouse'], tagMode: 'ANY' }, tablesByTag);

    expect(scope.excludedSchemas).toEqual(['sa']);
    expect(scope.excludedTables).toEqual(['sb.stat_one', 'sc.stat_two']);
  });

  it('both reach the request, neither cancelling the other', () => {
    const scope = resolveSchemaTagScope(schemaNames, tagsBySchema,
      { includeTags: [], excludeTags: ['system', 'clickhouse'], tagMode: 'ANY' }, tablesByTag);
    const request = buildSearchRequest(defaultSearchState(), scope, { limit: 50, offset: 0 });

    expect(request.excludeSchemas).toEqual(['sa']);
    expect(request.excludeTables).toEqual(['sb.stat_one', 'sc.stat_two']);
  });

  it('a tag on both schemas and tables contributes to both', () => {
    const scope = resolveSchemaTagScope(schemaNames, new Map([['sa', ['both']]]),
      { includeTags: [], excludeTags: ['both'], tagMode: 'ANY' },
      new Map([['both', ['sb.t']]]));

    expect(scope.excludedSchemas).toEqual(['sa']);
    expect(scope.excludedTables).toEqual(['sb.t']);
  });

  it('two tags covering the same table list it once', () => {
    const scope = resolveSchemaTagScope(schemaNames, tagsBySchema,
      { includeTags: [], excludeTags: ['a', 'b'], tagMode: 'ANY' },
      new Map([['a', ['sb.shared']], ['b', ['sb.shared', 'sc.only']]]));

    expect(scope.excludedTables).toEqual(['sb.shared', 'sc.only']);
  });

  it('omitting the table map leaves table exclusion empty, which is what include-only needs', () => {
    const scope = resolveSchemaTagScope(schemaNames, tagsBySchema,
      { includeTags: [], excludeTags: ['clickhouse'], tagMode: 'ANY' });

    expect(scope.excludedTables).toEqual([]);
  });

  it('survives the URL round trip alongside a schema tag', () => {
    const state = defaultSearchState();
    state.schemaTagFilter = { includeTags: [], excludeTags: ['system', 'clickhouse'], tagMode: 'ANY' };
    const query = new URLSearchParams(encodeSearchParams(state)).toString();
    const back = new URLSearchParams(query);
    const decoded = decodeSearchParams({
      get: (name: string) => back.get(name),
      has: (name: string) => back.has(name)
    });

    expect(decoded.schemaTagFilter.excludeTags).toEqual(['system', 'clickhouse']);

    // and the decoded intent still resolves to both exclusion lists
    const request = buildSearchRequest(decoded,
      resolveSchemaTagScope(schemaNames, tagsBySchema, decoded.schemaTagFilter, tablesByTag),
      { limit: 50, offset: 0 });
    expect(request.excludeSchemas).toEqual(['sa']);
    expect(request.excludeTables).toEqual(['sb.stat_one', 'sc.stat_two']);
  });
});

describe('column drill-down param', () => {

  const params = (record: Record<string, string>) => ({
    get: (name: string) => (name in record ? record[name] : null),
    has: (name: string) => name in record
  });

  it('round-trips and reaches the request', () => {
    const state = defaultSearchState();
    state.columns = ['app_main.user_profile.user_id'];

    const encoded = encodeSearchParams(state);
    expect(encoded['columns']).toBe('app_main.user_profile.user_id');

    const decoded = decodeSearchParams(params(encoded));
    expect(decoded.columns).toEqual(['app_main.user_profile.user_id']);
    expect(buildSearchRequest(decoded, UNCONSTRAINED_TAG_SCOPE, { limit: 25, offset: 0 }).columns)
      .toEqual(['app_main.user_profile.user_id']);
  });

  it('is omitted when unset, so an untouched view keeps a clean URL', () => {
    expect(encodeSearchParams(defaultSearchState())['columns']).toBeUndefined();
    expect(buildSearchRequest(defaultSearchState(), UNCONSTRAINED_TAG_SCOPE, { limit: 25, offset: 0 }).columns)
      .toBeNull();
  });

  it('travels alongside the table scope, which is how the lens links to the workbench', () => {
    const state = defaultSearchState();
    state.columns = ['app_main.user_profile.user_id'];
    state.scope = { schemas: [], tables: ['app_main.user_profile'], tableMode: 'ANY' };

    const decoded = decodeSearchParams(params(encodeSearchParams(state)));
    const request = buildSearchRequest(decoded, UNCONSTRAINED_TAG_SCOPE, { limit: 25, offset: 0 });

    expect(request.tables).toEqual(['app_main.user_profile']);
    expect(request.columns).toEqual(['app_main.user_profile.user_id']);
  });

  it('carries the join drill-down as tables + ALL, needing no filter of its own', () => {
    const state = defaultSearchState();
    state.scope = { schemas: [], tables: ['app_main.group_member', 'app_main.user_group'], tableMode: 'ALL' };

    const request = buildSearchRequest(decodeSearchParams(params(encodeSearchParams(state))),
      UNCONSTRAINED_TAG_SCOPE, { limit: 25, offset: 0 });

    expect(request.tables).toEqual(['app_main.group_member', 'app_main.user_group']);
    expect(request.tableMode).toBe('ALL');
  });
});

describe('search-url: hasExamples', () => {

  /**
   * "Which queries can I actually plan?" is the question this answers — an
   * example is what makes a query runnable, since the corpus text carries `?`.
   */
  it('round-trips through the URL', () => {
    const params = encodeSearchParams({...defaultSearchState(), hasExamples: true});
    expect(params['hasExamples']).toBe('1');

    const decoded = decodeSearchParams(new URLSearchParams(params as Record<string, string>));
    expect(decoded.hasExamples).toBeTrue();
  });

  /** Omitted when off, so an untouched workbench keeps a clean URL. */
  it('is absent from the URL when off', () => {
    expect(encodeSearchParams(defaultSearchState())['hasExamples']).toBeUndefined();
  });

  it('reaches the request body only when set', () => {
    const paging = {limit: 50, offset: 0};
    expect(buildSearchRequest(defaultSearchState(), UNCONSTRAINED_TAG_SCOPE, paging).hasExamples).toBeNull();
    expect(buildSearchRequest({...defaultSearchState(), hasExamples: true}, UNCONSTRAINED_TAG_SCOPE, paging)
      .hasExamples).toBeTrue();
  });
});

/** The index twin of the column drill-down (sqlx-yj0). */
describe('index drill-down param', () => {

  const params = (record: Record<string, string>) => ({
    get: (name: string) => (name in record ? record[name] : null),
    has: (name: string) => name in record
  });

  it('round-trips and reaches the request', () => {
    const state = defaultSearchState();
    state.indexes = ['app.orders.orders_status_idx'];

    const encoded = encodeSearchParams(state);
    expect(encoded['indexes']).toBe('app.orders.orders_status_idx');

    const decoded = decodeSearchParams(params(encoded));
    expect(decoded.indexes).toEqual(['app.orders.orders_status_idx']);
    expect(buildSearchRequest(decoded, UNCONSTRAINED_TAG_SCOPE, { limit: 25, offset: 0 }).indexes)
      .toEqual(['app.orders.orders_status_idx']);
  });

  it('is omitted when unset, so an untouched view keeps a clean URL', () => {
    expect(encodeSearchParams(defaultSearchState())['indexes']).toBeUndefined();
    expect(buildSearchRequest(defaultSearchState(), UNCONSTRAINED_TAG_SCOPE, { limit: 25, offset: 0 }).indexes)
      .toBeNull();
  });

  it('travels alongside the table scope and a column drill-down', () => {
    const state = defaultSearchState();
    state.indexes = ['app.orders.PRIMARY'];
    state.columns = ['app.orders.status'];
    state.scope = { schemas: [], tables: ['app.orders'], tableMode: 'ANY' };

    const request = buildSearchRequest(decodeSearchParams(params(encodeSearchParams(state))),
      UNCONSTRAINED_TAG_SCOPE, { limit: 25, offset: 0 });

    expect(request.tables).toEqual(['app.orders']);
    expect(request.columns).toEqual(['app.orders.status']);
    expect(request.indexes).toEqual(['app.orders.PRIMARY']);
  });
});

/** Any query carrying an index hint, whatever index it names (sqlx-yj0.5). */
describe('hinted param', () => {

  it('round-trips each action and reaches the request', () => {
    for (const action of ['ANY', 'USE', 'FORCE', 'IGNORE'] as const) {
      const state = defaultSearchState();
      state.hinted = action;

      const encoded = encodeSearchParams(state);
      expect(encoded['hinted']).toBe(action);

      const decoded = decodeSearchParams(reader(encoded));
      expect(decoded.hinted).toBe(action);
      expect(buildSearchRequest(decoded, UNCONSTRAINED_TAG_SCOPE, { limit: 25, offset: 0 }).hinted).toBe(action);
    }
  });

  it('is omitted when unset, so an untouched view keeps a clean URL', () => {
    expect(encodeSearchParams(defaultSearchState())['hinted']).toBeUndefined();
    expect(buildSearchRequest(defaultSearchState(), UNCONSTRAINED_TAG_SCOPE, { limit: 25, offset: 0 }).hinted)
      .toBeNull();
  });

  it('ignores a value that is not an action, and forgives case', () => {
    expect(decodeSearchParams(reader({ hinted: 'sometimes' })).hinted).toBeNull();
    expect(decodeSearchParams(reader({ hinted: 'force' })).hinted).toBe('FORCE');
  });
});
