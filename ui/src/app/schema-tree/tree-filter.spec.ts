import {filterSchemaTree, matchesTagFilter, matchesTreeFilter, matchesTypeFilter, TreeFilterContext} from './tree-filter';

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

describe('matchesTagFilter', () => {

  it('excluded tags hide the schema outright', () => {
    expect(matchesTagFilter(['legacy'], { includeTags: [], excludeTags: ['legacy'], tagMode: 'ANY' })).toBeFalse();
    expect(matchesTagFilter(['core'], { includeTags: [], excludeTags: ['legacy'], tagMode: 'ANY' })).toBeTrue();
  });

  it('include tags narrow by ANY or ALL', () => {
    expect(matchesTagFilter(['core'], { includeTags: ['core', 'legacy'], excludeTags: [], tagMode: 'ANY' })).toBeTrue();
    expect(matchesTagFilter(['core'], { includeTags: ['core', 'legacy'], excludeTags: [], tagMode: 'ALL' })).toBeFalse();
    expect(matchesTagFilter(['core', 'legacy'], { includeTags: ['core', 'legacy'], excludeTags: [], tagMode: 'ALL' })).toBeTrue();
  });

  it('exclude wins over include', () => {
    expect(matchesTagFilter(['core', 'legacy'], { includeTags: ['core'], excludeTags: ['legacy'], tagMode: 'ANY' })).toBeFalse();
  });
});

describe('filterSchemaTree (the whole sidebar chain)', () => {

  const schemas = [
    { name: 'tdb', tables: [{ name: 'member_session', view: false }, { name: 'active_member_sessions', view: true }] },
    { name: 'other', tables: [{ name: 'branch', view: false }] }
  ];
  const tagsBySchema = new Map([['tdb', ['core']], ['other', ['legacy']]]);
  const context = (overrides: Partial<TreeFilterContext> = {}): TreeFilterContext => ({
    needle: '', typeFilter: 'all',
    tagFilter: { includeTags: [], excludeTags: [], tagMode: 'ANY' },
    tagsBySchema, ...overrides
  });

  it('tag include narrows and exclude hides, composed with everything else', () => {
    expect(filterSchemaTree(schemas, context({ tagFilter: { includeTags: ['core'], excludeTags: [], tagMode: 'ANY' } }))
      .map(schema => schema.name)).toEqual(['tdb']);
    expect(filterSchemaTree(schemas, context({ tagFilter: { includeTags: [], excludeTags: ['legacy'], tagMode: 'ANY' } }))
      .map(schema => schema.name)).toEqual(['tdb']);
  });

  it('tags compose with the dot-anchored needle', () => {
    // branch's schema is excluded by tag, so the needle finds nothing
    expect(filterSchemaTree(schemas, context({
      needle: '.branch', tagFilter: { includeTags: [], excludeTags: ['legacy'], tagMode: 'ANY' }
    }))).toEqual([]);
  });

  it('tags compose with the type toggle', () => {
    const result = filterSchemaTree(schemas, context({
      typeFilter: 'views', tagFilter: { includeTags: ['core'], excludeTags: [], tagMode: 'ANY' }
    }));
    expect(result.map(schema => schema.name)).toEqual(['tdb']);
    expect(result[0].tables.map(table => table.name)).toEqual(['active_member_sessions']);
  });

  it('mixed-case schema names match the lower-cased tag map', () => {
    const upper = [{ name: 'TDB', tables: [{ name: 't' }] }];
    expect(filterSchemaTree(upper, context({ tagFilter: { includeTags: ['core'], excludeTags: [], tagMode: 'ANY' } }))
      .map(schema => schema.name)).toEqual(['TDB']);
  });

  it('an empty tag map makes include filters hide everything — the symptom of a failed tags load', () => {
    expect(filterSchemaTree(schemas, context({
      tagsBySchema: new Map(), tagFilter: { includeTags: ['core'], excludeTags: [], tagMode: 'ANY' }
    }))).toEqual([]);
  });
});
