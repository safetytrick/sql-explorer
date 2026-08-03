import {parseExplain} from './explain-parser';

describe('parseExplain', () => {

  it('parses TSV with NULL handling and numbers', () => {
    const text = [
      'id\tselect_type\ttable\tpartitions\ttype\tpossible_keys\tkey\tkey_len\tref\trows\tfiltered\tExtra',
      '1\tSIMPLE\tmember_session\tNULL\tALL\tms_idx,other_idx\tNULL\tNULL\tNULL\t95321\t10.00\tUsing where'
    ].join('\n');

    const parsed = parseExplain(text);

    expect(parsed.format).toBe('tabular');
    expect(parsed.error).toBeNull();
    expect(parsed.rows.length).toBe(1);
    const row = parsed.rows[0];
    expect(row.table).toBe('member_session');
    expect(row.partitions).toBeNull();
    expect(row.type).toBe('ALL');
    expect(row.possibleKeys).toEqual(['ms_idx', 'other_idx']);
    expect(row.key).toBeNull();
    expect(row.rows).toBe(95321);
    expect(row.filtered).toBe(10);
    expect(row.extra).toBe('Using where');
  });

  it('parses the mysql CLI grid with pipe borders', () => {
    const text = [
      '+----+-------------+--------+------+---------------+------+---------+------+------+----------+-------+',
      '| id | select_type | table  | type | possible_keys | key  | key_len | ref  | rows | filtered | Extra |',
      '+----+-------------+--------+------+---------------+------+---------+------+------+----------+-------+',
      '|  1 | SIMPLE      | branch | ref  | idx           | idx  | 4       | const|   12 |   100.00 | NULL  |',
      '+----+-------------+--------+------+---------------+------+---------+------+------+----------+-------+'
    ].join('\n');

    const parsed = parseExplain(text);

    expect(parsed.format).toBe('tabular');
    expect(parsed.rows.length).toBe(1);
    expect(parsed.rows[0].table).toBe('branch');
    expect(parsed.rows[0].key).toBe('idx');
    expect(parsed.rows[0].rows).toBe(12);
  });

  it('parses \\G vertical output', () => {
    const text = [
      '*************************** 1. row ***************************',
      '           id: 1',
      '  select_type: SIMPLE',
      '        table: member_session',
      '   partitions: NULL',
      '         type: range',
      'possible_keys: ms_idx',
      '          key: ms_idx',
      '      key_len: 4',
      '          ref: NULL',
      '         rows: 200',
      '     filtered: 50.00',
      '        Extra: Using index condition',
      '*************************** 2. row ***************************',
      '           id: 1',
      '  select_type: SIMPLE',
      '        table: branch',
      '         type: eq_ref',
      '          key: PRIMARY',
      '         rows: 1',
      '     filtered: 100.00'
    ].join('\n');

    const parsed = parseExplain(text);

    expect(parsed.format).toBe('vertical');
    expect(parsed.rows.length).toBe(2);
    expect(parsed.rows[0].type).toBe('range');
    expect(parsed.rows[0].extra).toBe('Using index condition');
    expect(parsed.rows[1].table).toBe('branch');
    expect(parsed.rows[1].key).toBe('PRIMARY');
  });

  it('flattens FORMAT=JSON access nodes', () => {
    const text = JSON.stringify({
      query_block: {
        select_id: 1,
        nested_loop: [
          { table: { table_name: 'member_session', access_type: 'ALL',
                     possible_keys: ['ms_idx'], rows_examined_per_scan: 95321, filtered: '10.00',
                     attached_condition: '(`tdb`.`ms`.`branch_id` is not null)' } },
          { table: { table_name: 'branch', access_type: 'eq_ref', key: 'PRIMARY',
                     key_length: '144', ref: ['tdb.ms.branch_id'],
                     rows_examined_per_scan: 1, filtered: '100.00', using_filesort: true } }
        ]
      }
    });

    const parsed = parseExplain(text);

    expect(parsed.format).toBe('json');
    expect(parsed.rows.length).toBe(2);
    expect(parsed.rows[0].table).toBe('member_session');
    expect(parsed.rows[0].type).toBe('ALL');
    expect(parsed.rows[0].rows).toBe(95321);
    expect(parsed.rows[0].extra).toContain('Using where');
    expect(parsed.rows[1].key).toBe('PRIMARY');
    expect(parsed.rows[1].extra).toContain('Using filesort');
  });

  it('reports errors instead of silently yielding nothing', () => {
    expect(parseExplain('').rows.length).toBe(0);
    expect(parseExplain('').error).toBeNull();

    const junk = parseExplain('this is not explain output\nat all');
    expect(junk.rows.length).toBe(0);
    expect(junk.error).toContain('no EXPLAIN header');

    const badJson = parseExplain('{ not json');
    expect(badJson.format).toBe('json');
    expect(badJson.error).toContain('invalid JSON');
  });
});
