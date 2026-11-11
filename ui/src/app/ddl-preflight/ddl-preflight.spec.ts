import {decodePreflightParams, encodePreflightParams, sortChecks} from './ddl-preflight';

describe('ddl-preflight url and checks', () => {
  const reader = (params: Record<string, string>) => ({ get: (name: string) => params[name] ?? null });

  it('keeps the statement and a non-default threshold in the link', () => {
    const params = encodePreflightParams({ddl: ' ALTER TABLE account DROP COLUMN x ', longSeconds: 5});
    expect(params).toEqual({'var-ddl': 'ALTER TABLE account DROP COLUMN x', 'var-long': '5'});
    expect(decodePreflightParams(reader(params))).toEqual({ddl: 'ALTER TABLE account DROP COLUMN x', longSeconds: 5});
  });

  it('omits defaults and reads a bad threshold as the default', () => {
    expect(encodePreflightParams({ddl: '', longSeconds: 1})).toEqual({});
    expect(decodePreflightParams(reader({'var-long': 'soon'})).longSeconds).toBe(1);
  });

  it('puts what blocks a change before what informs', () => {
    const sorted = sortChecks([
      {severity: 'OK', title: 'a', detail: ''}, {severity: 'BLOCK', title: 'b', detail: ''},
      {severity: 'INFO', title: 'c', detail: ''}, {severity: 'WARN', title: 'd', detail: ''}]);
    expect(sorted.map(c => c.title)).toEqual(['b', 'd', 'c', 'a']);
  });
});
