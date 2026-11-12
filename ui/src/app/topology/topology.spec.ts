import {convertToParamMap} from '@angular/router';
import {
  arrange, decodeTopologyParams, encodeTopologyParams, formatSeconds, Load, opensQueries, percent, serverQueriesParams,
  shownInLegend, split,
  TopologyCluster, TopologyServer
} from './topology';

const load = (read: [number, number], write: [number, number], other: [number, number],
              unknown: [number, number] = [0, 0]): Load => ({
  statements: 3,
  executions: read[0] + write[0] + other[0] + unknown[0],
  seconds: read[1] + write[1] + other[1] + unknown[1],
  secondsPerDay: null,
  shares: [
    {kind: 'READ', statements: 1, executions: read[0], seconds: read[1]},
    {kind: 'WRITE', statements: 1, executions: write[0], seconds: write[1]},
    {kind: 'OTHER', statements: 1, executions: other[0], seconds: other[1]},
    {kind: 'UNKNOWN', statements: 1, executions: unknown[0], seconds: unknown[1]}
  ]
});

const server = (name: string, roles: string[]): TopologyServer => ({
  name, roles, schemaSource: false, status: 'READ', load: null, window: null, shareOfTime: null, reason: null
});

describe('topology', () => {

  it('reads and writes the URL, omitting defaults and clearing an old var-server', () => {
    expect(decodeTopologyParams(convertToParamMap({}))).toEqual({by: 'time'});
    expect(decodeTopologyParams(convertToParamMap({'var-server': 'shard1', 'var-by': 'executions'})))
      .toEqual({by: 'executions'});
    // anything unknown is the default, not an error
    expect(decodeTopologyParams(convertToParamMap({'var-by': 'bogus'})).by).toBe('time');
    expect(encodeTopologyParams({by: 'time'})).toEqual({'var-server': null, 'var-by': null});
    expect(encodeTopologyParams({by: 'executions'})).toEqual({'var-server': null, 'var-by': 'executions'});
  });

  it('opens a server with figures in the workbench, scoped to its source', () => {
    const replica = {...server('shard1-replica', ['replica']), load: load([1, 1], [0, 0], [0, 0])};
    expect(opensQueries(replica)).toBeTrue();
    expect(serverQueriesParams(replica)).toEqual({sources: 'shard1-replica'});
    // not captured or unreachable: nothing to open
    expect(opensQueries(server('shard1-etl', ['analytics']))).toBeFalse();
  });

  it('splits by time or by executions', () => {
    const l = load([900, 10], [100, 30], [0, 0]);

    expect(split(l, 'executions').map(s => s.fraction)).toEqual([0.9, 0.1, 0, 0]);
    expect(split(l, 'time').map(s => s.fraction)).toEqual([0.25, 0.75, 0, 0]);
    expect(split(load([0, 0], [0, 0], [0, 0]), 'time')).toEqual([]);
    expect(split(null, 'time')).toEqual([]);
  });

  it('keeps a sliver from reading as none', () => {
    expect(percent(0.004)).toBe('<1%');
    expect(percent(0)).toBe('0%');
    expect(percent(0.625)).toBe('63%');
    expect(percent(null)).toBe('—');
  });

  it('formats seconds at a readable scale', () => {
    expect(formatSeconds(0)).toBe('0 s');
    expect(formatSeconds(0.25)).toBe('250 ms');
    expect(formatSeconds(4.2)).toBe('4.2 s');
    expect(formatSeconds(600)).toBe('10 min');
    expect(formatSeconds(36000)).toBe('10.0 h');
    expect(formatSeconds(864000)).toBe('10.0 d');
  });

  it('draws a primary over the rest, and a cluster without one on a single row', () => {
    const cluster = (servers: TopologyServer[]): TopologyCluster =>
      ({name: 'shard1', servers, load: load([0, 0], [0, 0], [0, 0])});
    const primary = server('shard1', ['primary']);
    const replica = server('shard1-replica', ['replica', 'sampling']);

    expect(arrange(cluster([primary, replica]))).toEqual({top: [primary], below: [replica]});
    const solo = server('local', []);
    expect(arrange(cluster([solo]))).toEqual({top: [solo], below: []});
  });

  it('keys unknown only when some statements could not be classed', () => {
    const none = split(load([1, 1], [1, 1], [0, 0]), 'time');
    expect(none.filter(shownInLegend).map(s => s.kind)).toEqual(['READ', 'WRITE', 'OTHER']);
    const some = split(load([1, 1], [1, 1], [0, 0], [1, 2]), 'time');
    expect(some.filter(shownInLegend).map(s => s.kind)).toEqual(['READ', 'WRITE', 'OTHER', 'UNKNOWN']);
  });
});
