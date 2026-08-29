/**
 * Turns a statement full of `?` placeholders into a fillable form, and the
 * filled form back into a statement.
 *
 * <p>The corpus stores MySQL digest text, which already carries one `?` per
 * parameter. So the shape of the form is not something we invent — it is read
 * off the statement the profile already has, and everything except the values
 * stays fixed. That is what makes a form-built example land on the same digest
 * as the page it was built from, where a pasted one can drift.
 */

/** How a placeholder should be filled, which decides the input rendered for it. */
export type PlaceholderKind = 'number' | 'text' | 'date' | 'datetime' | 'time' | 'list';

export interface Placeholder {
  /** Index of the `?` in the source text. */
  offset: number;
  /** The column this placeholder is compared against, when one could be found. */
  column: string | null;
  /** The column's SQL type, when the schema knew it. */
  columnType: string | null;
  kind: PlaceholderKind;
  /** What the user has typed. For `list`, one entry per element. */
  value: string;
  /** For `list` only — the values entered so far. */
  values: string[];
}

/** A column's type as the columns endpoint reports it. */
export interface ColumnType {
  name: string;
  type: string;
}

const NUMERIC = /^(TINY|SMALL|MEDIUM|BIG)?INT|^DECIMAL|^NUMERIC|^FLOAT|^DOUBLE|^BIT/i;
const DATE_ONLY = /^DATE$/i;
const DATETIME = /^DATETIME|^TIMESTAMP/i;
const TIME_ONLY = /^TIME$/i;

/**
 * Find every `?` in `sql` and describe how to fill it.
 *
 * <p>Placeholders are located by scanning rather than by parsing. The corpus
 * text has already been through MySQL's digest, so every literal is a `?` and
 * there are no string literals left for a `?` to hide inside — which is what
 * makes scanning safe here and would not make it safe on arbitrary SQL.
 */
export function readPlaceholders(sql: string, columnTypes: ColumnType[] = []): Placeholder[] {
  const byName = new Map(columnTypes.map(column => [column.name.toLowerCase(), column.type]));
  const placeholders: Placeholder[] = [];

  for (let i = 0; i < sql.length; i++) {
    if (sql[i] !== '?') { continue; }

    const column = columnBefore(sql, i);
    const columnType = column ? byName.get(column.toLowerCase()) ?? null : null;
    const inList = insideInList(sql, i);

    placeholders.push({
      offset: i,
      column,
      columnType,
      kind: inList ? 'list' : kindOf(columnType),
      value: '',
      values: ['']
    });
  }
  return placeholders;
}

/**
 * The column a placeholder is being compared against, by scanning left for the
 * nearest identifier.
 *
 * <p>Deliberately a heuristic. `total > ?` and `id IN (?)` resolve cleanly;
 * a placeholder inside a function call or a bare `SELECT ?` resolves to
 * nothing, and that is fine — an untyped placeholder still gets a text input,
 * which is strictly better than refusing to render the form.
 */
export function columnBefore(sql: string, at: number): string | null {
  const before = sql.slice(0, at);
  // the last backtick-quoted or bare identifier ahead of an operator
  const match = before.match(/[`"]?([A-Za-z_][A-Za-z0-9_$]*)[`"]?\s*(?:=|<>|!=|<=|>=|<|>|\bLIKE\b|\bIN\b|\bBETWEEN\b)\s*(?:\([^()]*)?$/i);
  return match ? match[1] : null;
}

/** Whether the placeholder at `at` sits inside an `IN ( … )`. */
export function insideInList(sql: string, at: number): boolean {
  const before = sql.slice(0, at);
  const open = before.lastIndexOf('(');
  if (open < 0) { return false; }
  // an unclosed paren, immediately preceded by IN
  if (before.slice(open).includes(')')) { return false; }
  return /\bIN\s*$/i.test(before.slice(0, open));
}

/** The input to render, from the column's SQL type. Text when unknown. */
export function kindOf(columnType: string | null): PlaceholderKind {
  if (!columnType) { return 'text'; }
  if (NUMERIC.test(columnType)) { return 'number'; }
  if (DATE_ONLY.test(columnType)) { return 'date'; }
  if (DATETIME.test(columnType)) { return 'datetime'; }
  if (TIME_ONLY.test(columnType)) { return 'time'; }
  return 'text';
}

/**
 * Render one filled placeholder as a SQL literal.
 *
 * <p>Escaping here is ergonomics rather than a safety boundary, and it is worth
 * being clear about why: the statement goes to `POST /query-examples`, which
 * parses it to derive a digest. A badly escaped value fails to parse and is
 * rejected, or parses differently and lands under another digest. Both are
 * loud. Nothing is executed by submitting an example.
 */
export function toLiteral(placeholder: Placeholder, raw: string): string {
  const value = raw.trim();
  if (value === '') { return 'NULL'; }

  // A list's *elements* are typed by the column, not by the list-ness: `id IN
  // (1, 2, 3)` against a BIGINT column must not become `IN ('1', '2', '3')`,
  // which is a different query to the planner even where MySQL would coerce it.
  const effective = placeholder.kind === 'list' ? kindOf(placeholder.columnType) : placeholder.kind;

  if (effective === 'number') {
    // left as written when it looks numeric; quoted otherwise so a typo is a
    // parse error at the endpoint rather than silently mangled SQL
    return /^-?\d+(\.\d+)?$/.test(value) ? value : quote(value);
  }
  return quote(value);
}

/** Single-quoted, with embedded quotes doubled — the portable escape. */
export function quote(value: string): string {
  return `'${value.replace(/'/g, '\'\'')}'`;
}

/**
 * Substitute the filled values back into the statement.
 *
 * <p>Works right-to-left so each replacement cannot shift the offsets of the
 * ones not yet done — the offsets were recorded against the original string.
 *
 * <p>A `list` placeholder expands to as many elements as were entered, which is
 * the one place the form changes the statement's *shape* rather than its
 * values. That is deliberate: the corpus renders every `IN` as `IN (?)`
 * because repeat collapsing reduces it, so the stored text cannot say how many
 * elements the real statement had. Choosing that count is choosing which
 * performance shape the example represents.
 */
export function buildStatement(sql: string, placeholders: Placeholder[]): string {
  let out = sql;
  for (const placeholder of [...placeholders].sort((a, b) => b.offset - a.offset)) {
    const literal = placeholder.kind === 'list'
      ? (placeholder.values.filter(v => v.trim() !== '').map(v => toLiteral(placeholder, v)).join(', ') || 'NULL')
      : toLiteral(placeholder, placeholder.value);
    out = out.slice(0, placeholder.offset) + literal + out.slice(placeholder.offset + 1);
  }
  return out;
}

/** Whether every placeholder has something in it. */
export function isComplete(placeholders: Placeholder[]): boolean {
  return placeholders.every(placeholder => placeholder.kind === 'list'
    ? placeholder.values.some(value => value.trim() !== '')
    : placeholder.value.trim() !== '');
}
