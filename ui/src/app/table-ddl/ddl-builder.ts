/**
 * A very small statement builder for one table: the handful of changes a
 * migration is usually made of, written out as the SQL the pre-flight check
 * reads. Pure, so every statement it can produce is specified without a page.
 *
 * <p>It writes statements; it does not validate them. A column type is taken
 * as typed, because the check that follows is what judges it.
 */

export type BuilderOp =
  'add-column' | 'modify-column' | 'rename-column' | 'drop-column' | 'convert' | 'add-index' | 'drop-index';

export const BUILDER_OPS: {op: BuilderOp; label: string}[] = [
  {op: 'add-column', label: 'Add column'},
  {op: 'modify-column', label: 'Modify column'},
  {op: 'rename-column', label: 'Rename column'},
  {op: 'drop-column', label: 'Drop column'},
  {op: 'convert', label: 'Convert charset'},
  {op: 'add-index', label: 'Create index'},
  {op: 'drop-index', label: 'Drop index'}
];

export interface BuilderState {
  op: BuilderOp;
  /** The column to modify, rename or drop. */
  column: string;
  /** The new column's name, or the renamed column's new name. */
  newName: string;
  /** As typed: VARCHAR(255), BIGINT UNSIGNED … */
  type: string;
  nullable: boolean;
  /** Empty for none. */
  defaultValue: string;
  /** Empty to leave the charset alone. */
  charset: string;
  collation: string;
  /** Where an added column goes; empty for the end. */
  after: string;
  /** The index's name, to create or drop. */
  index: string;
  /** The new index's columns, in order. */
  indexColumns: string[];
  unique: boolean;
  /** ALGORITHM= to state; empty to let MySQL choose. */
  algorithm: BuilderAlgorithm;
}

export type BuilderAlgorithm = '' | 'INSTANT' | 'INPLACE' | 'COPY';
export const BUILDER_ALGORITHMS: BuilderAlgorithm[] = ['', 'INSTANT', 'INPLACE', 'COPY'];

export const EMPTY_BUILDER: BuilderState = {
  op: 'add-column', column: '', newName: '', type: '', nullable: true, defaultValue: '', charset: '',
  collation: '', after: '', index: '', indexColumns: [], unique: false, algorithm: ''
};

/** `name`, with any backtick doubled as MySQL escapes it. */
export function quote(identifier: string): string {
  return '`' + identifier.replace(/`/g, '``') + '`';
}

function qualified(schema: string, table: string): string {
  return `${quote(schema)}.${quote(table)}`;
}

/** A default as typed, quoted unless it is a number, NULL, a function call or already quoted. */
function defaultLiteral(value: string): string {
  const v = value.trim();
  if (/^(null|current_timestamp(\(\d*\))?|true|false)$/i.test(v) || /^-?\d+(\.\d+)?$/.test(v)
    || /^'.*'$/.test(v) || /^\(.*\)$/.test(v)) {
    return v;
  }
  return `'${v.replace(/'/g, '\'\'')}'`;
}

function columnDefinition(state: BuilderState): string {
  const parts = [state.type.trim()];
  if (state.charset.trim()) {
    parts.push(`CHARACTER SET ${state.charset.trim()}`);
    if (state.collation.trim()) { parts.push(`COLLATE ${state.collation.trim()}`); }
  }
  parts.push(state.nullable ? 'NULL' : 'NOT NULL');
  if (state.defaultValue.trim()) { parts.push(`DEFAULT ${defaultLiteral(state.defaultValue)}`); }
  return parts.join(' ');
}

/** What is still missing before a statement can be written; empty when it can. */
export function builderMissing(state: BuilderState): string[] {
  const missing: string[] = [];
  const need = (ok: boolean, what: string) => { if (!ok) { missing.push(what); } };
  switch (state.op) {
    case 'add-column':
      need(!!state.newName.trim(), 'a column name');
      need(!!state.type.trim(), 'a type');
      break;
    case 'modify-column':
      need(!!state.column, 'a column');
      need(!!state.type.trim(), 'a type');
      break;
    case 'rename-column':
      need(!!state.column, 'a column');
      need(!!state.newName.trim(), 'a new name');
      break;
    case 'drop-column':
      need(!!state.column, 'a column');
      break;
    case 'convert':
      need(!!state.charset.trim(), 'a character set');
      break;
    case 'add-index':
      need(!!state.index.trim(), 'an index name');
      need(state.indexColumns.length > 0, 'at least one column');
      break;
    case 'drop-index':
      need(!!state.index, 'an index');
      break;
  }
  return missing;
}

/**
 * The statement, or null while something it needs is missing. An algorithm
 * is another item of an ALTER TABLE's comma-separated list, but follows
 * CREATE INDEX and DROP INDEX after a space: MySQL rejects either the other way.
 */
export function buildStatement(schema: string, table: string, state: BuilderState): string | null {
  if (builderMissing(state).length) { return null; }
  const target = qualified(schema, table);
  const algorithm = state.algorithm ? `ALGORITHM=${state.algorithm}` : '';
  const alter = (clause: string) => `ALTER TABLE ${target}\n  ${clause}` + (algorithm ? `,\n  ${algorithm}` : '');
  const option = algorithm ? `\n  ${algorithm}` : '';
  switch (state.op) {
    case 'add-column':
      return alter(`ADD COLUMN ${quote(state.newName.trim())} ${columnDefinition(state)}`
        + (state.after ? ` AFTER ${quote(state.after)}` : ''));
    case 'modify-column':
      return alter(`MODIFY COLUMN ${quote(state.column)} ${columnDefinition(state)}`);
    case 'rename-column':
      return alter(`RENAME COLUMN ${quote(state.column)} TO ${quote(state.newName.trim())}`);
    case 'drop-column':
      return alter(`DROP COLUMN ${quote(state.column)}`);
    case 'convert':
      return alter(`CONVERT TO CHARACTER SET ${state.charset.trim()}`
        + (state.collation.trim() ? ` COLLATE ${state.collation.trim()}` : ''));
    case 'add-index':
      return `CREATE ${state.unique ? 'UNIQUE ' : ''}INDEX ${quote(state.index.trim())}\n  ON ${target} (`
        + state.indexColumns.map(quote).join(', ') + ')' + option;
    case 'drop-index':
      return state.index.toUpperCase() === 'PRIMARY'
        ? alter('DROP PRIMARY KEY')
        : `DROP INDEX ${quote(state.index)} ON ${target}` + option;
  }
}

/** The column as it is declared now, to start a MODIFY from. */
export interface ExistingColumn {
  columnName: string;
  typeName: string;
  columnSize: number;
  decimalDigits: number;
  isNullable: string;
  columnDefault: string | null;
  characterSet: string | null;
  collation: string | null;
}

const SIZED = new Set(['varchar', 'char', 'varbinary', 'binary']);
const SCALED = new Set(['decimal', 'numeric']);

/** VARCHAR(255), DECIMAL(10,2), BIGINT: the column's type as a MODIFY would restate it. */
export function declaredType(column: ExistingColumn): string {
  const type = column.typeName ?? '';
  const lower = type.toLowerCase();
  if (SIZED.has(lower) && column.columnSize > 0) { return `${type}(${column.columnSize})`; }
  if (SCALED.has(lower) && column.columnSize > 0) { return `${type}(${column.columnSize},${column.decimalDigits})`; }
  return type;
}

/**
 * The builder filled from an existing column, so MODIFY starts from what the
 * column is: a MODIFY restates the whole definition, and anything left out of
 * it — NOT NULL, the default, the charset — is silently dropped.
 */
export function fromExistingColumn(state: BuilderState, column: ExistingColumn): BuilderState {
  return {
    ...state,
    column: column.columnName,
    type: declaredType(column),
    nullable: (column.isNullable ?? 'YES').toUpperCase() !== 'NO',
    defaultValue: column.columnDefault ?? '',
    charset: column.characterSet ?? '',
    collation: column.collation ?? ''
  };
}

/** A conventional name for a new index: idx_<table>_<first two columns>. */
export function suggestIndexName(table: string, columns: string[]): string {
  return ['idx', table, ...columns.slice(0, 2)].join('_').slice(0, 64);
}
