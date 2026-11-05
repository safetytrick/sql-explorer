import {analyze, Analysis, Role, Source} from './sql-scopes';
import {tokenize} from './sql-tokens';
import {assignColors, preferredSlot} from './table-colors';
import {constrainingInstances, instancesOf, paintSql} from './paint';

/** `[text, role, source name-or-alias]` for every marked token. */
function marked(a: Analysis): [string, Role, string | undefined][] {
  return a.tokens.flatMap((t, i) => {
    const m = a.marks[i];
    return m ? [[t.text, m.role, m.source && (m.source.alias ?? m.source.name)] as [string, Role, string | undefined]] : [];
  });
}

/** The source a given occurrence of `text` was attributed to. */
function sourceOf(a: Analysis, text: string, nth = 0): Source | undefined {
  const hits = a.tokens.map((t, i) => (t.text === text ? a.marks[i]?.source : null)).filter(x => x !== null);
  return hits[nth] ?? undefined;
}

describe('tokenize', () => {
  it('gives back the input exactly, whatever it is', () => {
    for (const sql of [
      'SELECT `a` . `id` FROM `app_core` . `account` `a` WHERE `a` . `id` IN (...)',
      'select \'it\'\'s\', "q\\"", 1.5e3, 0x1F, @x, @@sql_mode -- tail',
      'SELECT /* unterminated',
      'SELECT \'unterminated',
      'a<=>b ->> c',
    ]) {
      expect(tokenize(sql).map(t => t.text).join('')).toBe(sql);
    }
  });

  it('keeps non-reserved keywords as names', () => {
    const words = tokenize('select status, date, name from t').filter(t => t.kind === 'word');
    expect(words.filter(w => w.keyword).map(w => w.keyword)).toEqual(['SELECT', 'FROM']);
  });
});

describe('analyze', () => {

  it('tells the two sides of a self-join apart', () => {
    const a = analyze('SELECT a.id, b.id FROM account a JOIN account b ON a.parent_id = b.id');
    const left = sourceOf(a, 'a');
    const right = sourceOf(a, 'b');
    expect(left).toBeDefined();
    expect(right).toBeDefined();
    expect(left).not.toBe(right);
    expect(sourceOf(a, 'parent_id')).toBe(left);
    expect(sourceOf(a, 'id', 0)).toBe(left);
    expect(sourceOf(a, 'id', 1)).toBe(right);
    expect(sourceOf(a, 'id', 2)).toBe(right);

    const colors = assignColors(a.sources);
    expect(colors.get(left!)).not.toBe(colors.get(right!));
  });

  it('reads digest text: backticks, spaces round the dots, placeholders', () => {
    const a = analyze('SELECT `s` . `name` FROM `app_core` . `site` `s` JOIN `account_site` `x` ' +
      'ON `x` . `site_id` = `s` . `id` WHERE `x` . `account_id` = ?');
    expect(marked(a)).toEqual([
      ['`s`', 'qualifier', 's'], ['.', 'dot', 's'], ['`name`', 'column', 's'],
      ['`app_core`', 'schema', undefined], ['`site`', 'table', 's'], ['`s`', 'alias', 's'],
      ['`account_site`', 'table', 'x'], ['`x`', 'alias', 'x'],
      ['`x`', 'qualifier', 'x'], ['.', 'dot', 'x'], ['`site_id`', 'column', 'x'],
      ['`s`', 'qualifier', 's'], ['.', 'dot', 's'], ['`id`', 'column', 's'],
      ['`x`', 'qualifier', 'x'], ['.', 'dot', 'x'], ['`account_id`', 'column', 'x'],
    ]);
    expect(a.sources.map(s => [s.schema, s.name, s.alias])).toEqual([
      ['app_core', 'site', 's'], [undefined, 'account_site', 'x'],
    ]);
  });

  it('attributes unqualified columns only when one table is in scope', () => {
    const one = analyze('SELECT id, name FROM account WHERE status = ? ORDER BY name');
    expect(sourceOf(one, 'id')?.name).toBe('account');
    expect(sourceOf(one, 'status')?.name).toBe('account');

    const two = analyze('SELECT id FROM account, site WHERE status = ?');
    expect(sourceOf(two, 'id')).toBeUndefined();
    expect(sourceOf(two, 'status')).toBeUndefined();
  });

  it('does not mistake select-list aliases or functions for columns', () => {
    const a = analyze('SELECT COUNT(id) AS total, max(created) latest FROM account ORDER BY total');
    expect(sourceOf(a, 'total', 0)).toBeUndefined();
    expect(sourceOf(a, 'total', 1)).toBeUndefined();
    expect(sourceOf(a, 'latest')).toBeUndefined();
    expect(a.marks[a.tokens.findIndex(t => t.text === 'max')]?.role).toBe('function');
    expect(sourceOf(a, 'id')?.name).toBe('account');
    expect(sourceOf(a, 'created')?.name).toBe('account');
  });

  it('gives a subquery its own scope, and lets it see the outer one', () => {
    const a = analyze('SELECT a.id FROM account a WHERE EXISTS ' +
      '(SELECT 1 FROM site s WHERE s.account_id = a.id AND deleted = 0)');
    const outer = sourceOf(a, 'a');
    expect(sourceOf(a, 'account_id')?.alias).toBe('s');
    expect(sourceOf(a, 'id', 1)).toBe(outer);       // correlated reference
    expect(sourceOf(a, 'deleted')?.alias).toBe('s'); // the inner scope's only table
  });

  it('marks derived tables and CTEs as virtual sources', () => {
    const d = analyze('SELECT t.n FROM (SELECT count(*) n FROM account) AS t');
    expect(d.sources.map(s => [s.kind, s.name])).toEqual([['table', 'account'], ['derived', 't']]);
    expect(sourceOf(d, 'n', 0)?.kind).toBe('derived');   // t.n
    expect(sourceOf(d, 'n', 1)).toBeUndefined();          // the inner alias

    const c = analyze('WITH recent AS (SELECT id FROM account WHERE created > ?) ' +
      'SELECT r.id FROM recent r JOIN recent r2 ON r2.id = r.id');
    const [decl, , r, r2] = c.sources;
    expect(decl.kind).toBe('cte');
    expect(r.kind).toBe('cte');
    expect(r2.kind).toBe('cte');
    expect(r).not.toBe(r2);
    expect(sourceOf(c, 'id', 1)).toBe(r);
  });

  it('keeps UNION branches separate', () => {
    const a = analyze('SELECT id FROM account UNION ALL SELECT id FROM site');
    expect(sourceOf(a, 'id', 0)?.name).toBe('account');
    expect(sourceOf(a, 'id', 1)?.name).toBe('site');
  });

  it('handles INSERT, UPDATE and multi-table DELETE', () => {
    const ins = analyze('INSERT INTO account (id, name) VALUES (?, ?) ON DUPLICATE KEY UPDATE name = VALUES(name)');
    expect(sourceOf(ins, 'id')?.name).toBe('account');
    expect(sourceOf(ins, 'name', 2)?.name).toBe('account');
    expect(ins.sources.length).toBe(1);

    const upd = analyze('UPDATE account a JOIN site s ON s.account_id = a.id SET a.touched = ? WHERE s.id = ?');
    expect(sourceOf(upd, 'touched')?.alias).toBe('a');

    const del = analyze('DELETE a FROM account a JOIN site s ON s.account_id = a.id');
    expect(del.sources.length).toBe(2);
    expect(sourceOf(del, 'a', 0)).toBe(sourceOf(del, 'a', 1));
  });

  it('survives a truncated statement', () => {
    expect(() => analyze('SELECT a.id FROM account a JOIN (SELECT')).not.toThrow();
    expect(() => analyze('SELECT a.id FROM account a WHERE a.x IN (...) AND (')).not.toThrow();
  });
});

describe('assignColors', () => {
  it('gives a table the same colour in every query where it appears once', () => {
    const one = analyze('SELECT id FROM account');
    const other = analyze('SELECT a.id FROM app_core.account AS a WHERE a.x = ?');
    expect(assignColors(one.sources).get(one.sources[0])).toBe(preferredSlot('account'));
    expect(assignColors(other.sources).get(other.sources[0])).toBe(preferredSlot('account'));
  });

  it('never repeats a colour within ten instances', () => {
    const tables = Array.from({length: 10}, (_, i) => `t${i}`);
    const a = analyze(`SELECT 1 FROM ${tables.map(t => `account ${t}`).join(', ')}`);
    expect(new Set(assignColors(a.sources).values()).size).toBe(10);
  });
});

describe('analyze with column lists (sqlx-4vp)', () => {
  const schema = [
    {name: 'account', columns: ['id', 'name', 'parent_id']},
    {name: 'site', columns: ['id', 'account_id', 'region']},
  ];
  const painted = (sql: string, hint = schema) => paintSql(sql, hint).analysis;

  it('places a column only one table in scope has', () => {
    const a = painted('SELECT name, region, id FROM account a JOIN site s ON s.account_id = a.id');
    expect(sourceOf(a, 'name')?.alias).toBe('a');
    expect(sourceOf(a, 'region')?.alias).toBe('s');
    expect(sourceOf(a, 'id', 0)).toBeUndefined();   // both have it
  });

  it('leaves a self-join\'s shared columns unplaced', () => {
    const a = painted('SELECT name FROM account a JOIN account p ON p.id = a.parent_id');
    expect(sourceOf(a, 'name')).toBeUndefined();
  });

  it('will not guess past a table it knows nothing about', () => {
    const a = painted('SELECT name FROM account a JOIN audit_log l ON l.ref = a.id');
    expect(sourceOf(a, 'name')).toBeUndefined();
    const c = painted('WITH x AS (SELECT 1 AS name) SELECT name FROM account a, x');
    expect(sourceOf(c, 'name', 1)).toBeUndefined();
  });

  it('looks outward for a correlated column the inner table lacks', () => {
    const a = painted('SELECT a.id FROM account a WHERE EXISTS (SELECT 1 FROM site s WHERE account_id = a.id AND name = ?)');
    expect(sourceOf(a, 'account_id')?.alias).toBe('s');
    expect(sourceOf(a, 'name')?.alias).toBe('a');
  });

  it('changes nothing without column lists', () => {
    expect(sourceOf(analyze('SELECT name FROM account a JOIN site s ON s.id = a.id'), 'name')).toBeUndefined();
  });
});

describe('paintSql', () => {
  it('lists each table instance with the colour it was given', () => {
    const p = paintSql('SELECT 1 FROM app_core.account a JOIN account b ON b.id = a.parent_id JOIN site ON site.id = ?');
    expect(p.tables.map(t => [t.label, t.name, t.schema, t.kind])).toEqual([
      ['a', 'account', 'app_core', 'table'], ['b', 'account', undefined, 'table'], ['site', 'site', undefined, 'table'],
    ]);
    expect(p.tables[0].cssClass).toBe(`sqt-c${p.tables[0].slot}`);
    expect(p.tables[0].slot).toBe(preferredSlot('account'));
    expect(new Set(p.tables.map(t => t.slot)).size).toBe(3);
  });

  it('records which instance constrains which column, ignoring the select list', () => {
    const p = paintSql('SELECT b.name FROM account a JOIN account b ON b.id = a.parent_id WHERE a.status = ?');
    const both = instancesOf(p, 'app_core', 'account');
    expect(both.map(t => t.label)).toEqual(['a', 'b']);
    expect(constrainingInstances(p, both, 'parent_id').map(t => t.label)).toEqual(['a']);
    expect(constrainingInstances(p, both, 'id').map(t => t.label)).toEqual(['b']);
    expect(constrainingInstances(p, both, 'name')).toEqual([]);   // select list only
  });

  it('gives a table that appears once every one of its columns', () => {
    const p = paintSql('SELECT 1 FROM account a, site s WHERE region = ?');
    const one = instancesOf(p, null, 'site');
    expect(constrainingInstances(p, one, 'region')).toEqual(one);
  });
});
