import {parseColumnRef, parseTableRef, tableRefOf} from './refs';

describe('parseTableRef', () => {

  it('splits schema and table', () => {
    expect(parseTableRef('app_other.trigger_stat_get_user_list'))
      .toEqual({ schema: 'app_other', table: 'trigger_stat_get_user_list' });
  });

  it('leaves the schema null when the reference is bare', () => {
    expect(parseTableRef('users')).toEqual({ schema: null, table: 'users' });
  });

  it('parses from the right, so a dotted schema keeps the table in place', () => {
    // left-to-right indexing put "b" in the table slot and dropped "c"
    expect(parseTableRef('a.b.c')).toEqual({ schema: 'a.b', table: 'c' });
  });
});

describe('parseColumnRef', () => {

  it('splits schema, table and column', () => {
    expect(parseColumnRef('app_other.user_profile.uuid'))
      .toEqual({ schema: 'app_other', table: 'user_profile', column: 'uuid' });
  });

  it('handles a column qualified only by its table', () => {
    expect(parseColumnRef('user_profile.uuid'))
      .toEqual({ schema: null, table: 'user_profile', column: 'uuid' });
  });

  it('handles a bare column name', () => {
    expect(parseColumnRef('uuid')).toEqual({ schema: null, table: null, column: 'uuid' });
  });

  it('parses from the right when there are more segments than expected', () => {
    // the old hand-rolled split took pair[0..2] positionally and mis-slotted
    // everything past the third segment
    expect(parseColumnRef('a.b.c.d')).toEqual({ schema: 'a.b', table: 'c', column: 'd' });
  });
});

describe('tableRefOf', () => {

  it('returns the table part of a column reference', () => {
    expect(tableRefOf('app_other.user_profile.uuid')).toBe('app_other.user_profile');
    expect(tableRefOf('user_profile.uuid')).toBe('user_profile');
  });

  it('returns null for a bare column', () => {
    expect(tableRefOf('uuid')).toBeNull();
  });
});
