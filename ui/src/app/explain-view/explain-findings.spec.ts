import {ExplainRow} from './explain-parser';
import {explainFindings} from './explain-findings';

const row = (patch: Partial<ExplainRow>): ExplainRow => ({
  id: '1', selectType: 'SIMPLE', table: 'account', partitions: null, type: 'ref', possibleKeys: [],
  key: null, keyLen: null, ref: null, rows: 10, filtered: 100, extra: null, ...patch
});

describe('explainFindings', () => {
  it('names a full table scan, and whether an index was passed over', () => {
    const [none] = explainFindings([row({type: 'ALL', rows: 50000})]);
    expect(none.severity).toBe('bad');
    expect(none.text).toContain('full table scan (about 50,000 rows): no index fits');

    const [passed] = explainFindings([row({type: 'ALL', rows: 50000, possibleKeys: ['site_idx']})]);
    expect(passed.text).toContain('although site_idx could have been used');
  });

  it('does not alarm about scanning a tiny table', () => {
    expect(explainFindings([row({type: 'ALL', rows: 12})])).toEqual([]);
  });

  it('reads filesort, temporary tables, join buffers and dependent subqueries', () => {
    const texts = explainFindings([
      row({extra: 'Using where; Using temporary; Using filesort'}),
      row({table: 'site', type: 'ALL', rows: 5000, extra: 'Using join buffer (hash join)'}),
      row({table: 'note', selectType: 'DEPENDENT SUBQUERY'})
    ]).map(finding => finding.text);
    expect(texts.some(text => text.includes('temporary table'))).toBeTrue();
    expect(texts.some(text => text.includes('filesort'))).toBeTrue();
    expect(texts.some(text => text.includes('join buffer'))).toBeTrue();
    expect(texts.some(text => text.includes('re-run for every row'))).toBeTrue();
  });

  it('flags heavy filtering after the read, and notes a covering index', () => {
    const findings = explainFindings([row({rows: 20000, filtered: 1.5, key: 'site_idx', extra: 'Using where; Using index'})]);
    expect(findings.map(finding => finding.severity)).toEqual(['warn', 'note']);
    expect(findings[1].text).toContain('covered by site_idx');
  });

  it('puts the worst first', () => {
    const severities = explainFindings([
      row({key: 'a', extra: 'Using index'}),
      row({type: 'ALL', rows: 9000})
    ]).map(finding => finding.severity);
    expect(severities).toEqual(['bad', 'note']);
  });
});
