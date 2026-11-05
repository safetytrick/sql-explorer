import {decodeWorkbenchParams, encodeWorkbenchParams} from './workbench-url';

function reader(params: Record<string, string>) {
  return { get: (name: string) => params[name] ?? null };
}

describe('workbench-url', () => {
  it('omits the param when nothing is expanded', () => {
    expect(encodeWorkbenchParams({ expanded: [], fullSql: false, detailPane: false })).toEqual({});
  });

  it('round-trips expanded rows through var-expanded', () => {
    const params = encodeWorkbenchParams({ expanded: ['v3:aaa', 'v3:bbb'], fullSql: false, detailPane: false });
    expect(params).toEqual({ 'var-expanded': 'v3:aaa,v3:bbb' });
    expect(decodeWorkbenchParams(reader(params))).toEqual({ expanded: ['v3:aaa', 'v3:bbb'], fullSql: false, detailPane: false });
  });

  it('reads an absent, empty or repeated param leniently', () => {
    expect(decodeWorkbenchParams(reader({}))).toEqual({ expanded: [], fullSql: false, detailPane: false });
    expect(decodeWorkbenchParams(reader({ 'var-expanded': '' }))).toEqual({ expanded: [], fullSql: false, detailPane: false });
    expect(decodeWorkbenchParams(reader({ 'var-expanded': 'a,,a, b' }))).toEqual({ expanded: ['a', 'b'], fullSql: false, detailPane: false });
  });

  // every row's whole SQL, with the rows collapsed back as exceptions (sqlx-ywy.1)
  it('round-trips full-SQL mode beside its exceptions', () => {
    const params = encodeWorkbenchParams({ expanded: ['v3:aaa'], fullSql: true, detailPane: false });
    expect(params).toEqual({ 'var-expanded': 'v3:aaa', 'var-sql': 'full' });
    expect(decodeWorkbenchParams(reader(params))).toEqual({ expanded: ['v3:aaa'], fullSql: true, detailPane: false });
    expect(decodeWorkbenchParams(reader({ 'var-sql': 'something' })).fullSql).toBe(false);
  });

  it('writes the detail pane only when it is on', () => {
    expect(encodeWorkbenchParams({ expanded: [], fullSql: false, detailPane: true })).toEqual({ 'var-detail': '1' });
    expect(decodeWorkbenchParams(reader({ 'var-detail': '1' })).detailPane).toBe(true);
    expect(decodeWorkbenchParams(reader({})).detailPane).toBe(false);
  });
});
