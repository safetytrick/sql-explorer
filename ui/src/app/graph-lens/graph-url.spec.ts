import {decodeGraphParams, DEFAULT_LAYERS, encodeGraphParams, graphOwnedParamPatch, GraphUrlState, ParamReader} from './graph-url';

function reader(params: Record<string, string>): ParamReader {
  return { get: (name: string) => params[name] ?? null };
}

describe('encodeGraphParams', () => {

  it('emits nothing for the default state (clean URL)', () => {
    expect(encodeGraphParams({ table: null, layers: { ...DEFAULT_LAYERS }, tag: null, source: null }))
      .toEqual({});
  });

  it('prefixes graph-owned params with var-', () => {
    const params = encodeGraphParams({
      table: 'member_session',
      layers: { fk: true, join: true, co: true, self: true, view: true },
      tag: null, source: null
    });
    expect(params['var-table']).toBe('member_session');
    expect(params['var-layers']).toBe('fk,join,co,self,view');
  });

  it('keeps scope params unprefixed, tag winning over source', () => {
    expect(encodeGraphParams({ table: null, layers: { ...DEFAULT_LAYERS }, tag: 'core', source: null }))
      .toEqual({ tag: 'core' });
    expect(encodeGraphParams({ table: null, layers: { ...DEFAULT_LAYERS }, tag: null, source: 'search' }))
      .toEqual({ source: 'search' });
    expect(encodeGraphParams({ table: null, layers: { ...DEFAULT_LAYERS }, tag: 'core', source: 'search' }))
      .toEqual({ tag: 'core' });
  });

  it('encodes all-layers-off as a sentinel instead of an empty param', () => {
    const params = encodeGraphParams({
      table: null,
      layers: { fk: false, join: false, co: false, self: false, view: false },
      tag: null, source: null
    });
    expect(params['var-layers']).toBe('none');
  });
});

describe('graphOwnedParamPatch (merge writes)', () => {

  it('touches only var- params, null-clearing at defaults', () => {
    expect(graphOwnedParamPatch(null, { ...DEFAULT_LAYERS }))
      .toEqual({ 'var-table': null, 'var-layers': null, 'var-layout': null });
    expect(graphOwnedParamPatch('member_session', { fk: true, join: true, co: true, self: true, view: true }))
      .toEqual({ 'var-table': 'member_session', 'var-layers': 'fk,join,co,self,view', 'var-layout': null });
    expect(graphOwnedParamPatch(null, { ...DEFAULT_LAYERS }, 'grid')['var-layout']).toBeNull();
    expect(graphOwnedParamPatch(null, { ...DEFAULT_LAYERS }, 'force')['var-layout']).toBe('force');
    expect(graphOwnedParamPatch(null, { fk: false, join: false, co: false, self: false, view: false })['var-layers'])
      .toBe('none');
  });
});

describe('decodeGraphParams', () => {

  it('falls back to defaults on a bare URL', () => {
    const state = decodeGraphParams(reader({}));
    expect(state.table).toBeNull();
    expect(state.layers).toEqual(DEFAULT_LAYERS);
    expect(state.layers).toEqual({ fk: false, join: true, co: false, self: false, view: true });
    expect(state.layout).toBe('grid');
    expect(state.tag).toBeNull();
    expect(state.source).toBeNull();
  });

  it('reads var-layout=force as the simulation, and a link from when grid was opt-in as the grid', () => {
    expect(decodeGraphParams(reader({ 'var-layout': 'force' })).layout).toBe('force');
    expect(decodeGraphParams(reader({ 'var-layout': 'grid' })).layout).toBe('grid');
  });

  it('still reads the legacy ?table= highlight param', () => {
    expect(decodeGraphParams(reader({ table: 'branch' })).table).toBe('branch');
    // …but var-table wins when both are present
    expect(decodeGraphParams(reader({ table: 'branch', 'var-table': 'member' })).table).toBe('member');
  });

  it('ignores unknown layer names and honours the none sentinel', () => {
    expect(decodeGraphParams(reader({ 'var-layers': 'fk,bogus,view' })).layers)
      .toEqual({ fk: true, join: false, co: false, self: false, view: true });
    expect(decodeGraphParams(reader({ 'var-layers': 'none' })).layers)
      .toEqual({ fk: false, join: false, co: false, self: false, view: false });
  });

  it('round-trips every non-default state', () => {
    const states: GraphUrlState[] = [
      { table: 'member_session', layers: { fk: false, join: false, co: true, self: false, view: true }, layout: 'force', tag: null, source: null },
      { table: null, layers: { ...DEFAULT_LAYERS }, layout: 'grid', tag: 'core', source: null },
      { table: 'branch', layers: { fk: true, join: true, co: true, self: true, view: true }, layout: 'force', tag: null, source: 'search' },
      { table: null, layers: { fk: false, join: false, co: false, self: false, view: false }, layout: 'grid', tag: null, source: null }
    ];
    for (const state of states) {
      expect(decodeGraphParams(reader(encodeGraphParams(state)))).toEqual(state);
    }
  });
});
