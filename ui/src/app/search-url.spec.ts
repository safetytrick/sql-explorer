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
