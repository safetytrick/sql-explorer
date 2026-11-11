import {TableRollup} from '../api-types';
import {encodeTableSort, readTableSort, sortTables} from './sort-url';

function row(name: string, totalQueryTime: number, writesPerDay?: number): TableRollup {
  return {name, schema: 'app_main', numberOfQueries: 1, totalQueryTime, tags: [], view: false, writesPerDay};
}

describe('table sort URL', () => {
  it('omits the default and falls back to it for anything unknown', () => {
    expect(encodeTableSort('time')).toBeNull();
    expect(encodeTableSort('writes')).toBe('writes');
    expect(readTableSort('writes')).toBe('writes');
    expect(readTableSort('bogus')).toBe('time');
    expect(readTableSort(null)).toBe('time');
  });

  it('keeps the server order for time and ranks by writes per day otherwise', () => {
    const rows = [row('account', 30, 1), row('site', 20), row('account_site', 10, 50)];

    expect(sortTables(rows, 'time')).toBe(rows);
    expect(sortTables(rows, 'writes').map(r => r.name)).toEqual(['account_site', 'account', 'site']);
  });
});
