import {matchesTreeFilter, matchesTypeFilter} from './tree-filter';

describe('matchesTreeFilter', () => {

  // the two motivating examples: some_schema.table and some_schema.users
  it('\'some\' matches every table in a matching schema', () => {
    expect(matchesTreeFilter('some_schema', 'table', 'some')).toBeTrue();
    expect(matchesTreeFilter('some_schema', 'users', 'some')).toBeTrue();
  });

  it('\'.users\' anchors past the schema and matches table names only', () => {
    expect(matchesTreeFilter('some_schema', 'users', '.users')).toBeTrue();
    expect(matchesTreeFilter('some_schema', 'table', '.users')).toBeFalse();
    // a schema whose *name* contains "users" is not fooled by the dot anchor
    expect(matchesTreeFilter('users_schema', 'table', '.users')).toBeFalse();
  });

  it('plain terms still match table names alone', () => {
    expect(matchesTreeFilter('some_schema', 'member_session', 'session')).toBeTrue();
    expect(matchesTreeFilter('some_schema', 'branch', 'session')).toBeFalse();
  });

  it('terms can span the schema/table boundary', () => {
    expect(matchesTreeFilter('some_schema', 'users', 'schema.u')).toBeTrue();
    expect(matchesTreeFilter('some_schema', 'table', 'schema.u')).toBeFalse();
  });

  it('is case-insensitive and whitespace-tolerant', () => {
    expect(matchesTreeFilter('Some_Schema', 'Users', '.USERS')).toBeTrue();
    expect(matchesTreeFilter('some_schema', 'users', '  users  ')).toBeTrue();
  });

  it('an empty term matches everything', () => {
    expect(matchesTreeFilter('some_schema', 'users', '')).toBeTrue();
    expect(matchesTreeFilter('some_schema', 'users', '   ')).toBeTrue();
  });

  it('a trailing dot matches the schema side', () => {
    expect(matchesTreeFilter('some_schema', 'users', 'some_schema.')).toBeTrue();
    expect(matchesTreeFilter('other', 'users', 'some_schema.')).toBeFalse();
  });
});

describe('matchesTypeFilter', () => {

  it('splits tables and views when the flag is present', () => {
    expect(matchesTypeFilter('all', false)).toBeTrue();
    expect(matchesTypeFilter('all', true)).toBeTrue();
    expect(matchesTypeFilter('tables', false)).toBeTrue();
    expect(matchesTypeFilter('tables', true)).toBeFalse();
    expect(matchesTypeFilter('views', true)).toBeTrue();
    expect(matchesTypeFilter('views', false)).toBeFalse();
  });

  it('a missing flag (old backend payload) counts as not-a-view', () => {
    // this is the "tables shows everything, views shows nothing" state:
    // the backend serving the payload predates the view field
    expect(matchesTypeFilter('tables', undefined)).toBeTrue();
    expect(matchesTypeFilter('views', undefined)).toBeFalse();
    expect(matchesTypeFilter('all', undefined)).toBeTrue();
  });
});
