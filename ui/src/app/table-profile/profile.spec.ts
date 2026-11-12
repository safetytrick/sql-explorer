import {ColumnResult, decodeProfileParams, encodeProfileParams, findings, share, SignatureResult} from './profile';

describe('profile helpers', () => {
  const params = (values: Record<string, string>) => ({get: (name: string) => values[name] ?? null});

  it('round-trips the page state through the link, omitting what is unset', () => {
    const state = decodeProfileParams(params({'var-run': '12', 'var-server': 'app-etl', 'var-col': 'title'}));

    expect(state).toEqual({run: 12, server: 'app-etl', column: 'title', scan: null});
    expect(encodeProfileParams(state)).toEqual(
      {'var-run': '12', 'var-server': 'app-etl', 'var-col': 'title', 'var-scan': null});
    expect(decodeProfileParams(params({'var-run': 'x'})).run).toBeNull();
  });

  it('lists findings that matched, problems before information', () => {
    const signature = (name: string, rows: number, informational = false) =>
      ({signature: name, rows, informational} as SignatureResult);
    const column = {signatures: [signature('WINDOWS_1252_PUNCTUATION', 90, true), signature('UTF8_IN_LATIN1', 3),
      signature('LATIN1_TEXT', 0), signature('QUESTION_MARK', 7)]} as ColumnResult;

    expect(findings(column).map(f => f.signature)).toEqual(['QUESTION_MARK', 'UTF8_IN_LATIN1', 'WINDOWS_1252_PUNCTUATION']);
  });

  it('shows shares without claiming precision it does not have', () => {
    expect(share(0, 0)).toBe('—');
    expect(share(0, 500)).toBe('0%');
    expect(share(1, 20000)).toBe('<0.1%');
    expect(share(3, 200)).toBe('1.5%');
    expect(share(50, 100)).toBe('50%');
  });
});
