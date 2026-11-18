/**
 * The graph detail's Columns tab: every column of the selected table, in
 * table order, marked with the keys it takes part in. Pure: built from the
 * metadata payload the table lens already reads, plus the graph's own key
 * columns for the foreign keys, which the metadata payload does not mark per
 * column.
 */
import {GraphKeyColumn, TableMeta} from '../schema-lens.service';

export interface ColumnRow {
  name: string;
  /** The type as MySQL names it, with a length for the character types. */
  type: string;
  nullable: boolean;
  primary: boolean;
  /** A single-column unique index, other than the primary key. */
  unique: boolean;
  foreignKey: boolean;
  remarks: string | null;
}

const SIZED = /^(var)?(char|binary)$/i;

export function columnRows(meta: TableMeta, keys: GraphKeyColumn[] = []): ColumnRow[] {
  const lower = (name: string) => name.toLowerCase();
  const primary = new Set((meta.indices ?? []).filter(index => index.name === 'PRIMARY')
    .flatMap(index => index.columns.map(lower)));
  const unique = new Set((meta.indices ?? []).filter(index => index.unique && index.name !== 'PRIMARY'
    && index.columns.length === 1).map(index => lower(index.columns[0])));
  const foreign = new Set(keys.filter(key => key.foreignKey).map(key => lower(key.name)));
  return [...(meta.columns ?? [])]
    .sort((a, b) => (a.ordinal ?? 0) - (b.ordinal ?? 0))
    .map(column => {
      const typeName = (column.typeName ?? '').toLowerCase();
      const name = lower(column.columnName);
      return {
        name: column.columnName,
        type: SIZED.test(typeName) && column.columnSize ? `${typeName}(${column.columnSize})` : typeName,
        nullable: column.isNullable === 'YES',
        primary: primary.has(name),
        unique: unique.has(name) && !primary.has(name),
        foreignKey: foreign.has(name),
        remarks: column.remarks || null
      };
    });
}
