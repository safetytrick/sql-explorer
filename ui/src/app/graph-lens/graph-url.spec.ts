import {
  decodeGraphParams, DEFAULT_LAYERS, graphOwnedParamPatch, graphRedirectParams, GraphUrlState, ParamReader, tableGraphRedirectParams
} from './graph-url';

function reader(params: Record<string, string>): ParamReader {
  return { get: (name: string) => params[name] ?? null };
}

function encoded(state: GraphUrlState): Record<string, string> {
  const params: Record<string, string> = {};
  for (const [name, value] of Object.entries(graphOwnedParamPatch(state))) {
    if (value !== null) { params[name] = value; }
  }
  return params;
}

describe('graphOwnedParamPatch (merge writes)', () => {

  it('touches only var- params, null-clearing at defaults', () => {
    expect(graphOwnedParamPatch({ table: null, layers: { ...DEFAULT_LAYERS } })).toEqual(
      { 'var-table': null, 'var-layers': null, 'var-layout': null, 'var-tables': null, 'var-depth': null });
    expect(graphOwnedParamPatch({ table: 'account_site', layers: { fk: true, join: true, co: true, self: true, view: true } }))
      .toEqual({ 'var-table': 'account_site', 'var-layers': 'fk,join,co,self,view',
        'var-layout': null, 'var-tables': null, 'var-depth': null });
  });

  it('writes the force layout and all tables, since the grid and queried tables are the defaults', () => {
    const patch = graphOwnedParamPatch({ table: null, layers: { ...DEFAULT_LAYERS }, layout: 'force', allTables: true });
    expect(patch['var-layout']).toBe('force');
    expect(patch['var-tables']).toBe('all');
    expect(graphOwnedParamPatch({ table: null, layers: { ...DEFAULT_LAYERS }, layout: 'grid' })['var-layout']).toBeNull();
  });

  it('spells every layer off as none, since an empty param would be dropped', () => {
    expect(graphOwnedParamPatch({ table: null, layers: { fk: false, join: false, co: false, self: false, view: false } })['var-layers'])
      .toBe('none');
  });
});

describe('decodeGraphParams', () => {

  it('falls back to defaults on a bare URL: the grid, joins and views, queried tables', () => {
    const state = decodeGraphParams(reader({}));
    expect(state.table).toBeNull();
    expect(state.layers).toEqual(DEFAULT_LAYERS);
    expect(state.layers).toEqual({ fk: false, join: true, co: false, self: false, view: true });
    expect(state.layout).toBe('grid');
    expect(state.allTables).toBeFalse();
    expect(state.depth).toBeNull();
  });

  it('reads var-layout=force as the simulation, and a link from when grid was opt-in as the grid', () => {
    expect(decodeGraphParams(reader({ 'var-layout': 'force' })).layout).toBe('force');
    expect(decodeGraphParams(reader({ 'var-layout': 'grid' })).layout).toBe('grid');
  });

  it('still reads the legacy ?table= highlight param', () => {
    expect(decodeGraphParams(reader({ table: 'site' })).table).toBe('site');
    // …but var-table wins when both are present
    expect(decodeGraphParams(reader({ table: 'site', 'var-table': 'account' })).table).toBe('account');
  });

  it('ignores unknown layer names and honours the none sentinel', () => {
    expect(decodeGraphParams(reader({ 'var-layers': 'fk,bogus,view' })).layers)
      .toEqual({ fk: true, join: false, co: false, self: false, view: true });
    expect(decodeGraphParams(reader({ 'var-layers': 'none' })).layers)
      .toEqual({ fk: false, join: false, co: false, self: false, view: false });
  });

  it('round-trips every non-default state', () => {
    const states: GraphUrlState[] = [
      { table: 'account_site', layers: { fk: false, join: false, co: true, self: false, view: true },
        layout: 'force', allTables: false, depth: null },
      { table: null, layers: { ...DEFAULT_LAYERS }, layout: 'grid', allTables: true, depth: 2 },
      { table: 'site', layers: { fk: true, join: true, co: true, self: true, view: true }, layout: 'force', allTables: true, depth: null },
      { table: null, layers: { fk: false, join: false, co: false, self: false, view: false },
        layout: 'grid', allTables: false, depth: null }
    ];
    for (const state of states) {
      expect(decodeGraphParams(reader(encoded(state)))).toEqual(state);
    }
  });
});

describe('graphRedirectParams (old /graph links)', () => {

  it('turns a schema graph into a search scoped to the schema, with every table drawn', () => {
    expect(graphRedirectParams('app_main', { 'var-layers': 'fk,join' })).toEqual(
      { 'var-layers': 'fk,join', schemas: 'app_main', 'var-tables': 'all', view: 'graph' });
    // a scope already on the link is kept beside it, once
    expect(graphRedirectParams('app_main', { schemas: 'app_billing,app_main' })['schemas']).toBe('app_billing,app_main');
  });

  it('turns a tag graph into a search for the queries carrying the tag', () => {
    expect(graphRedirectParams(null, { tag: 'billing', 'var-table': 'invoice' })).toEqual(
      { tags: 'billing', 'var-table': 'invoice', view: 'graph' });
  });

  it('keeps a search graph\'s search as it is and drops the source marker', () => {
    expect(graphRedirectParams(null, { source: 'search', q: 'invoice', fullScan: 'YES', env: 'local' })).toEqual(
      { q: 'invoice', fullScan: 'YES', env: 'local', view: 'graph' });
  });

  it('carries the legacy ?table= highlight as var-table', () => {
    expect(graphRedirectParams(null, { table: 'site' })).toEqual({ 'var-table': 'site', view: 'graph' });
  });

  it('sends a bare /graph to the graph of every query', () => {
    expect(graphRedirectParams(null, {})).toEqual({ view: 'graph' });
  });
});

describe('neighbourhood depth (sqlx-aoy.9)', () => {

  it('round-trips as var-depth, and ignores what is not a positive whole number', () => {
    expect(graphOwnedParamPatch({ table: 'account', layers: { ...DEFAULT_LAYERS }, depth: 2 })['var-depth']).toBe('2');
    expect(graphOwnedParamPatch({ table: 'account', layers: { ...DEFAULT_LAYERS } })['var-depth']).toBeNull();
    expect(decodeGraphParams(reader({ 'var-depth': '3' })).depth).toBe(3);
    for (const bad of ['0', '-1', '1.5', 'far']) {
      expect(decodeGraphParams(reader({ 'var-depth': bad })).depth).withContext(bad).toBeNull();
    }
  });
});

describe('tableGraphRedirectParams (old /table-graph links)', () => {

  it('turns a table at a depth into the table selected, one hop further out, walking foreign keys too', () => {
    expect(tableGraphRedirectParams({ schema: 'app_main', table: 'account', depth: '2' }, {})).toEqual(
      { 'var-table': 'account', 'var-depth': '3', 'var-layers': 'fk,join,view', view: 'graph' });
    // depth 0 was the table and its own relationships: one hop
    expect(tableGraphRedirectParams({ schema: 'app_main', table: 'account' }, {})['var-depth']).toBe('1');
  });

  it('turns a schema into the schema with every table drawn', () => {
    expect(tableGraphRedirectParams({ schema: 'app_main' }, { env: 'local' })).toEqual(
      { env: 'local', schemas: 'app_main', 'var-tables': 'all', view: 'graph' });
  });

  it('turns a tag into the queries touching the tables carrying it', () => {
    expect(tableGraphRedirectParams({ tag: 'billing', depth: '1' }, {})).toEqual({ tableTags: 'billing', view: 'graph' });
  });

  it('sends the whole table graph to the graph of every query', () => {
    expect(tableGraphRedirectParams({}, {})).toEqual({ view: 'graph' });
  });
});
