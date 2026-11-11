import {columnPath, columnTarget, queryPath, schemaPath, tablePath, tableTarget, targetKey} from './tag-path';

/**
 * The shapes are a contract with the server, which derives a tag's type from
 * the segment before the last. Getting one wrong does not fail — it writes a
 * tag to an address nothing reads (sqlx-agl).
 */
describe('tag paths', () => {

  it('addresses a schema', () => {
    expect(schemaPath('app_other')).toBe('/schema/app_other');
  });

  it('addresses a table through its schema', () => {
    expect(tablePath('app_other', 'users')).toBe('/schema/app_other/table/users');
  });

  it('addresses a column through its table', () => {
    expect(columnPath('app_other', 'users', 'email')).toBe('/schema/app_other/table/users/column/email');
    expect(columnTarget('app_other', 'users', 'email'))
      .toEqual({type: 'column', schema: 'app_other', table: 'users', digest: '', column: 'email'});
  });

  it('keys two columns of one table apart, and a column apart from its table', () => {
    const keys = new Set([targetKey(columnTarget('app_other', 'users', 'a')), targetKey(columnTarget('app_other', 'users', 'b')),
      targetKey(tableTarget('app_other', 'users'))]);
    expect(keys.size).toBe(3);
  });

  it('addresses a query by digest', () => {
    expect(queryPath('abc123')).toBe('/query/abc123');
  });

  // The server reads the segment before the last as the tag's type, so these
  // are what make a bulk write land as schema/table/query rather than nothing.
  it('puts the kind in the second-to-last segment', () => {
    const kind = (path: string) => path.split('/').slice(-2)[0];
    expect(kind(schemaPath('app_other'))).toBe('schema');
    expect(kind(tablePath('app_other', 'users'))).toBe('table');
    expect(kind(queryPath('abc123'))).toBe('query');
  });
});
