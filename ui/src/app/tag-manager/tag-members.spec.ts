import {orderMembers, queryMembers, queryPath, schemaMembers, tableMembers} from './tag-members';
import {SchemaRollup, SqlProfile, Tag, TableRollup} from '../api-types';

function tag(id: number, name: string, path: string): Tag {
  return {id, name, path, type: 'x', author: null, createdOn: null};
}

describe('tag members', () => {

  it('resolves a schema detach against the selected tag, not the first one', () => {
    const schemas = [{
      name: 'app_other', numberOfQueries: 10, totalQueryTime: 5,
      tags: [tag(1, 'system', '/schema/app_other'), tag(2, 'clickhouse', '/schema/app_other')]
    }] as SchemaRollup[];

    expect(schemaMembers(schemas, 'clickhouse')[0].assocId).toBe(2);
    expect(schemaMembers(schemas, 'system')[0].assocId).toBe(1);
  });

  it('labels and links a table by schema and name', () => {
    const tables = [{
      name: 'users', schema: 'app_other', numberOfQueries: 3, totalQueryTime: 7, view: false,
      tags: [tag(9, 'clickhouse', '/schema/app_other/table/users')]
    }] as TableRollup[];

    const [member] = tableMembers(tables, 'clickhouse');
    expect(member.label).toBe('app_other.users');
    expect(member.link).toEqual(['/table', 'app_other', 'users']);
    expect(member.assocId).toBe(9);
  });

  it('resolves a query detach through the by-path lookup, since profiles carry no tags', () => {
    const query = {digest: 'abc123', runtimeMetrics: null} as SqlProfile;
    const byPath = {'/query/abc123': [tag(42, 'clickhouse', '/query/abc123')]};

    const [member] = queryMembers([query], 'clickhouse', byPath);
    expect(queryPath(query)).toBe('/query/abc123');
    expect(member.assocId).toBe(42);
    expect(member.link).toEqual(['/analyzed-sql', 'abc123']);
  });

  it('marks a member undetachable rather than guessing when no association is found', () => {
    const query = {digest: 'nope', runtimeMetrics: null} as SqlProfile;

    expect(queryMembers([query], 'clickhouse', {})[0].assocId).toBeNull();
  });

  it('groups by kind, heaviest first inside each group', () => {
    const ordered = orderMembers([
      {kind: 'query', label: 'q', link: [], assocId: null, numberOfQueries: 1, totalQueryTime: 900},
      {kind: 'table', label: 'light', link: [], assocId: null, numberOfQueries: 1, totalQueryTime: 1},
      {kind: 'schema', label: 's', link: [], assocId: null, numberOfQueries: 1, totalQueryTime: 5},
      {kind: 'table', label: 'heavy', link: [], assocId: null, numberOfQueries: 1, totalQueryTime: 50}
    ]);

    expect(ordered.map(member => member.label)).toEqual(['s', 'heavy', 'light', 'q']);
  });
});
