import {columnRows} from './table-columns';
import {TableMeta} from '../schema-lens.service';

describe('columnRows', () => {
  const index = (name: string, unique: boolean, ...columns: string[]) =>
    ({schema: 'app', unique, identifier: '', name, indexType: '', direction: '', cardinality: 0, pages: 0,
      filterCondition: '', columns});
  const meta = {
    indices: [index('PRIMARY', true, 'id'), index('uq_code', true, 'code'), index('uq_pair', true, 'site_id', 'code')],
    type: 'TABLE', remarks: null, underlyingTables: null, viewColumns: null,
    columns: [
      {columnName: 'site_id', remarks: '', typeName: 'INT', columnSize: 10, isNullable: 'YES', ordinal: 3},
      {columnName: 'id', remarks: '', typeName: 'INT', columnSize: 10, isNullable: 'NO', ordinal: 1},
      {columnName: 'code', remarks: 'the short code', typeName: 'VARCHAR', columnSize: 40, isNullable: 'NO', ordinal: 2}
    ]
  } as TableMeta;

  it('lists every column in table order, typed, with the keys each takes part in', () => {
    const rows = columnRows(meta, [{name: 'site_id', type: 'int', nullable: true, primary: false, unique: false,
      foreignKey: true}]);

    expect(rows.map(row => [row.name, row.type, row.nullable, row.primary, row.unique, row.foreignKey])).toEqual([
      ['id', 'int', false, true, false, false],
      ['code', 'varchar(40)', false, false, true, false],
      ['site_id', 'int', true, false, false, true]
    ]);
    expect(rows[1].remarks).toBe('the short code');
    expect(rows[0].remarks).toBeNull();
  });

  it('lists nothing for a table whose structure carries no columns', () => {
    expect(columnRows({...meta, columns: null})).toEqual([]);
  });
});
