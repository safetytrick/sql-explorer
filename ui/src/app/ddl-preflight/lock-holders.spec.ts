import {ServerSummary} from '../environment.service';
import {Preflight} from './ddl-preflight';
import {
  LockHolder, decodeServers, defaultServers, encodeServers, fmtAge, holderTables, oldestHolder
} from './lock-holders';

const SERVERS: ServerSummary[] = [
  {name: 'db-primary', schemaLoaded: true, roles: ['primary'], cluster: 'main'},
  {name: 'db-replica', schemaLoaded: true, roles: ['replica', 'sampling'], cluster: 'main'},
  {name: 'db-reports', schemaLoaded: true, roles: ['analytics'], cluster: 'main'},
];

function preflight(lockedTables: Preflight['lockedTables']): Preflight {
  return {schema: 'app', table: 'account_site', lockedTables} as Preflight;
}

function holder(trx: number | null, inState: number | null): LockHolder {
  return {schema: 'app', table: 'account_site', lockType: 'SHARED_READ', duration: 'TRANSACTION',
    status: 'GRANTED', processlistId: 7, user: 'reader', host: '10.0.0.1', command: 'Sleep', state: null,
    secondsInState: inState, transactionSeconds: trx};
}

describe('lock holders', () => {
  it('checks the table and only the relatives it locks exclusively', () => {
    const tables = holderTables(preflight([
      {schema: 'app', table: 'site_visit', role: 'CHILD', reach: 'EVERY_QUERY', waits: 'every query',
        constraints: [], reason: '', workload: null},
      {schema: 'app', table: 'account', role: 'PARENT', reach: 'WRITES', waits: 'UPDATE and DELETE',
        constraints: [], reason: '', workload: null},
    ]));
    expect(tables).toEqual([{schema: 'app', table: 'account_site'}, {schema: 'app', table: 'site_visit'}]);
  });

  it('has nothing to check without a table', () => {
    expect(holderTables(null)).toEqual([]);
    expect(holderTables({schema: null, table: null} as Preflight)).toEqual([]);
  });

  it('preselects the primary', () => {
    expect(defaultServers(SERVERS)).toEqual(['db-primary']);
  });

  it('keeps the default selection out of the URL', () => {
    expect(encodeServers(['db-primary'], SERVERS)).toBeNull();
    expect(encodeServers(['db-replica', 'db-primary'], SERVERS)).toBe('db-primary,db-replica');
    expect(encodeServers([], SERVERS)).toBe('');
  });

  it('restores a selection from the URL, dropping servers the environment no longer has', () => {
    expect(decodeServers(null, SERVERS)).toBeNull();
    expect(decodeServers('db-replica,gone', SERVERS)).toEqual(['db-replica']);
    expect(decodeServers('', SERVERS)).toEqual([]);
  });

  it('formats ages', () => {
    expect(fmtAge(null)).toBe('—');
    expect(fmtAge(45)).toBe('45s');
    expect(fmtAge(725)).toBe('12m 05s');
    expect(fmtAge(10920)).toBe('3h 02m');
  });

  it('takes the oldest holder by transaction age, else time in state', () => {
    const server = {server: 'db-primary', roles: [], error: null, note: null,
      holders: [holder(null, 900), holder(3600, 2), holder(40, 1)]};
    expect(oldestHolder(server)?.transactionSeconds).toBe(3600);
    expect(oldestHolder({...server, holders: []})).toBeNull();
  });
});
