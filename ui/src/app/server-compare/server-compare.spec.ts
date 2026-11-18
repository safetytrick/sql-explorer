import {convertToParamMap} from '@angular/router';
import {
  compact, CompareColumn, CompareRow, decodeCompareParams, encodeCompareParams, formatMs, hotCells
} from './server-compare';

describe('server comparison URL', () => {
  it('omits every default, so an untouched view keeps a clean URL', () => {
    const state = decodeCompareParams(convertToParamMap({}));
    expect(state).toEqual({cluster: null, by: 'QUERY', flags: [], all: false, sort: 'DIFFERENCE'});
    expect(Object.values(encodeCompareParams(state)).every(value => value === null)).toBeTrue();
  });

  it('round-trips every param, flags spelled lowercase with dashes in a fixed order', () => {
    const params = encodeCompareParams(
      {cluster: 'shard1', by: 'TABLE', flags: ['PLAN_DIFFERS', 'ON_PRIMARY'], all: true, sort: 'TIME'});
    expect(params).toEqual({'var-cluster': 'shard1', 'var-rows': 'table', 'var-flags': 'on-primary,plan-differs',
      'var-all': '1', 'var-sort': 'time'});
    expect(decodeCompareParams(convertToParamMap(params)))
      .toEqual({cluster: 'shard1', by: 'TABLE', flags: ['ON_PRIMARY', 'PLAN_DIFFERS'], all: true, sort: 'TIME'});
  });

  it('drops a flag it does not know rather than failing the link', () => {
    expect(decodeCompareParams(convertToParamMap({'var-flags': 'skewed,bogus, Replica-Writes'})).flags)
      .toEqual(['SKEWED', 'REPLICA_WRITES']);
  });
});

describe('hotCells', () => {
  const servers: CompareColumn[] = [
    {name: 'shard1', roles: ['primary'], read: true, windowDays: 10},
    {name: 'shard1-replica-a', roles: ['replica'], read: true, windowDays: 10},
    {name: 'shard1-replica-b', roles: ['replica'], read: true, windowDays: 10}
  ];
  const cell = (executionsPerDay: number, rows: number, ms: number, rowsAffected = 0) =>
    ({executions: executionsPerDay * 10, seconds: 1, secondsPerDay: 0.1, executionsPerDay,
      rowsExaminedPerExecution: rows, msPerExecution: ms, rowsAffected});
  const row = (flags: CompareRow['flags'], cells: CompareRow['cells'], kind = 'READ'): CompareRow =>
    ({key: 'a', text: 'select', kind, flags, difference: 1, secondsPerDay: 1, cells, queries: 1});

  it('points each flag at the cell it is about', () => {
    expect([...hotCells(row(['ON_PRIMARY'], [cell(1, 1, 1), cell(1, 1, 1), null]), servers)]).toEqual([0]);
    expect([...hotCells(row(['SKEWED'], [null, cell(90, 1, 1), cell(10, 1, 1)]), servers)]).toEqual([1]);
    expect([...hotCells(row(['PLAN_DIFFERS'], [null, cell(10, 10, 1), cell(10, 5000, 1)]), servers)]).toEqual([2]);
    expect([...hotCells(row(['LATENCY_DIFFERS'], [cell(10, 1, 90), cell(10, 1, 9), null]), servers)]).toEqual([0]);
    expect([...hotCells(row(['SINGLE_SERVER'], [null, null, cell(1, 1, 1)]), servers)]).toEqual([2]);
    expect([...hotCells(row(['REPLICA_WRITES'], [cell(1, 1, 1, 5), cell(1, 1, 1, 5), cell(1, 1, 1)], 'UPDATE'),
      servers)]).toEqual([1]);
  });

  it('marks nothing on a row with no flag', () => {
    expect(hotCells(row([], [cell(1, 1, 1), cell(1, 1, 1), cell(1, 1, 1)]), servers).size).toBe(0);
  });
});

describe('number formats', () => {
  it('compacts counts and keeps small ones readable', () => {
    expect(compact(0)).toBe('0');
    expect(compact(0.25)).toBe('0.3');
    expect(compact(950)).toBe('950');
    expect(compact(1234)).toBe('1.2k');
    expect(compact(45_000)).toBe('45k');
    expect(compact(3_400_000)).toBe('3.4M');
    expect(compact(null)).toBe('—');
  });

  it('formats time per execution in ms, then seconds', () => {
    expect(formatMs(0.42)).toBe('0.4 ms');
    expect(formatMs(12.3)).toBe('12 ms');
    expect(formatMs(3100)).toBe('3.1 s');
  });
});
