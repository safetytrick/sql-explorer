import {decodeWorkbenchParams, encodeWorkbenchParams} from './workbench-url';

function reader(params: Record<string, string>) {
  return { get: (name: string) => params[name] ?? null };
}

describe('workbench-url', () => {
  it('omits the param when nothing is expanded', () => {
    expect(encodeWorkbenchParams({ expanded: [] })).toEqual({});
  });

  it('round-trips expanded rows through var-expanded', () => {
    const params = encodeWorkbenchParams({ expanded: ['v3:aaa', 'v3:bbb'] });
    expect(params).toEqual({ 'var-expanded': 'v3:aaa,v3:bbb' });
    expect(decodeWorkbenchParams(reader(params))).toEqual({ expanded: ['v3:aaa', 'v3:bbb'] });
  });

  it('reads an absent, empty or repeated param leniently', () => {
    expect(decodeWorkbenchParams(reader({}))).toEqual({ expanded: [] });
    expect(decodeWorkbenchParams(reader({ 'var-expanded': '' }))).toEqual({ expanded: [] });
    expect(decodeWorkbenchParams(reader({ 'var-expanded': 'a,,a, b' }))).toEqual({ expanded: ['a', 'b'] });
  });
});
