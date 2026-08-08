import {filterSchemaTree, inertTags, matchesTagFilter, matchesTreeFilter, matchesTypeFilter, TreeFilterContext} from './tree-filter';

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

describe('two excluded tags', () => {

  const tagsBySchema = new Map([['sa', ['a']], ['sb', ['b']], ['sc', ['c']]]);
  const tree = [
    { name: 'sa', tables: [{ name: 't' }] },
    { name: 'sb', tables: [{ name: 't' }] },
    { name: 'sc', tables: [{ name: 't' }] }
  ];

  it('hides schemas carrying either excluded tag, not just the first', () => {
    const filter = { includeTags: [], excludeTags: ['a', 'b'], tagMode: 'ANY' as const };
    expect(matchesTagFilter(['a'], filter)).toBe(false);
    expect(matchesTagFilter(['b'], filter)).toBe(false);
    expect(matchesTagFilter(['c'], filter)).toBe(true);
  });

  it('drops both schemas from the tree', () => {
    const kept = filterSchemaTree(tree, {
      needle: '',
      typeFilter: 'all',
      tagFilter: { includeTags: [], excludeTags: ['a', 'b'], tagMode: 'ANY' },
      tagsBySchema
    });
    expect(kept.map(schema => schema.name)).toEqual(['sc']);
  });

  it('order does not matter', () => {
    const context = (excludeTags: string[]): TreeFilterContext => ({
      needle: '', typeFilter: 'all', tagsBySchema,
      tagFilter: { includeTags: [], excludeTags, tagMode: 'ANY' }
    });
    expect(filterSchemaTree(tree, context(['a', 'b'])).map(schema => schema.name))
      .toEqual(filterSchemaTree(tree, context(['b', 'a'])).map(schema => schema.name));
  });
});

/**
 * The real defect behind "I excluded two tags and only the first one worked":
 * the second tag was a *table* tag. This filter is schema-level, so it
 * resolved to no schemas and did nothing — while the chip looked active.
 */
describe('inertTags', () => {

  const tagsBySchema = new Map([['sa', ['schema-tag']], ['sb', []]]);

  it('flags a tag no schema carries', () => {
    expect([...inertTags(['schema-tag', 'table-only-tag'], { tagsBySchema })]).toEqual(['table-only-tag']);
  });

  it('flags a typo the same way', () => {
    expect(inertTags(['schema-taag'], { tagsBySchema }).has('schema-taag')).toBe(true);
  });

  it('flags nothing while the tag map is still empty', () => {
    // first paint, before the tags request lands — flagging here would mark
    // every chip inert for a moment
    expect([...inertTags(['schema-tag', 'anything'], { tagsBySchema: new Map() })]).toEqual([]);
  });

  it('an inert exclude tag genuinely changes nothing, which is why it is flagged', () => {
    const before = filterSchemaTree(
      [{ name: 'sa', tables: [{ name: 't' }] }, { name: 'sb', tables: [{ name: 't' }] }],
      { needle: '', typeFilter: 'all', tagsBySchema,
        tagFilter: { includeTags: [], excludeTags: [], tagMode: 'ANY' } });
    const after = filterSchemaTree(
      [{ name: 'sa', tables: [{ name: 't' }] }, { name: 'sb', tables: [{ name: 't' }] }],
      { needle: '', typeFilter: 'all', tagsBySchema,
        tagFilter: { includeTags: [], excludeTags: ['table-only-tag'], tagMode: 'ANY' } });
    expect(after).toEqual(before);
  });
});

describe('inertTags with table tags', () => {

  const tagsBySchema = new Map([['sa', ['schema-tag']], ['sb', []]]);
  const tablesByTag = new Map([['table-tag', ['sa.orders', 'sb.audit']], ['empty-tag', []]]);

  it('a table tag is NOT inert for exclusion — exclusion reaches tables', () => {
    expect(inertTags(['table-tag'], { tagsBySchema, tablesByTag }).has('table-tag')).toBe(false);
  });

  it('a table tag IS still inert for include — including narrows schemas only', () => {
    expect(inertTags(['table-tag'], { tagsBySchema }).has('table-tag')).toBe(true);
  });

  it('a genuinely dead tag stays inert either way', () => {
    expect(inertTags(['ghost'], { tagsBySchema, tablesByTag }).has('ghost')).toBe(true);
    expect(inertTags(['ghost'], { tagsBySchema }).has('ghost')).toBe(true);
  });

  it('a tag whose table list came back empty is inert', () => {
    expect(inertTags(['empty-tag'], { tagsBySchema, tablesByTag }).has('empty-tag')).toBe(true);
  });

  it('a schema tag stays active', () => {
    expect(inertTags(['schema-tag'], { tagsBySchema, tablesByTag }).has('schema-tag')).toBe(false);
  });

  it('nothing is inert while both carriers are still empty', () => {
    expect([...inertTags(['anything'], { tagsBySchema: new Map(), tablesByTag: new Map() })]).toEqual([]);
  });

  it('table tags alone are enough to leave the loading state', () => {
    // schema tags may legitimately be absent; the table map having loaded
    // means we can already tell a dead tag from a live one
    expect([...inertTags(['ghost'], { tagsBySchema: new Map(), tablesByTag })]).toEqual(['ghost']);
  });
});
