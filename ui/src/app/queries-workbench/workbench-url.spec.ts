import {decodeWorkbenchParams, encodeWorkbenchParams} from './workbench-url';

function reader(params: Record<string, string>) {
  return { get: (name: string) => params[name] ?? null };
}

describe('workbench-url', () => {
  it('omits the param when nothing is expanded', () => {
    expect(encodeWorkbenchParams({ expanded: [], fullSql: false, detailPane: false, hide: [] })).toEqual({});
  });

  it('round-trips expanded rows through var-expanded', () => {
    const params = encodeWorkbenchParams({ expanded: ['v3:aaa', 'v3:bbb'], fullSql: false, detailPane: false, hide: [] });
    expect(params).toEqual({ 'var-expanded': 'v3:aaa,v3:bbb' });
    expect(decodeWorkbenchParams(reader(params))).toEqual({ expanded: ['v3:aaa', 'v3:bbb'], fullSql: false, detailPane: false, hide: [] });
  });

  it('reads an absent, empty or repeated param leniently', () => {
    expect(decodeWorkbenchParams(reader({}))).toEqual({ expanded: [], fullSql: false, detailPane: false, hide: [] });
    expect(decodeWorkbenchParams(reader({ 'var-expanded': '' }))).toEqual({ expanded: [], fullSql: false, detailPane: false, hide: [] });
    expect(decodeWorkbenchParams(reader({ 'var-expanded': 'a,,a, b' }))).toEqual({ expanded: ['a', 'b'], fullSql: false, detailPane: false, hide: [] });
  });

  // every row's whole SQL, with the rows collapsed back as exceptions (sqlx-ywy.1)
  it('round-trips full-SQL mode beside its exceptions', () => {
    const params = encodeWorkbenchParams({ expanded: ['v3:aaa'], fullSql: true, detailPane: false, hide: [] });
    expect(params).toEqual({ 'var-expanded': 'v3:aaa', 'var-sql': 'full' });
    expect(decodeWorkbenchParams(reader(params))).toEqual({ expanded: ['v3:aaa'], fullSql: true, detailPane: false, hide: [] });
    expect(decodeWorkbenchParams(reader({ 'var-sql': 'something' })).fullSql).toBe(false);
  });

  it('writes the detail pane only when it is on', () => {
    expect(encodeWorkbenchParams({ expanded: [], fullSql: false, detailPane: true, hide: [] })).toEqual({ 'var-detail': '1' });
    expect(decodeWorkbenchParams(reader({ 'var-detail': '1' })).detailPane).toBe(true);
    expect(decodeWorkbenchParams(reader({})).detailPane).toBe(false);
  });

  // the side panels folded away, so the results get the page
  it('round-trips hidden panels in a fixed order, ignoring unknown names', () => {
    const params = encodeWorkbenchParams({ expanded: [], fullSql: false, detailPane: false, hide: ['filters', 'tree'] });
    expect(params).toEqual({ 'var-hide': 'tree,filters' });
    expect(decodeWorkbenchParams(reader(params)).hide).toEqual(['tree', 'filters']);
    expect(decodeWorkbenchParams(reader({ 'var-hide': 'filters,bogus' })).hide).toEqual(['filters']);
  });
});
