/**
 * Parses pasted MySQL EXPLAIN output. Formats are sniffed automatically:
 *
 * - tabular: TSV (spreadsheet/Workbench copy) or the mysql CLI grid with
 *   `|` pipes and `+---+` borders
 * - vertical: `EXPLAIN ...\G` blocks (`*************** 1. row ***************`)
 * - json: `EXPLAIN FORMAT=JSON` (the nested query_block document)
 *
 * Everything maps onto the classic tabular columns; JSON nodes contribute
 * what they carry. `NULL` strings become nulls.
 */

export interface ExplainRow {
  id: string | null;
  selectType: string | null;
  table: string | null;
  partitions: string | null;
  type: string | null;
  possibleKeys: string[];
  key: string | null;
  keyLen: string | null;
  ref: string | null;
  rows: number | null;
  filtered: number | null;
  extra: string | null;
}

export type ExplainFormat = 'tabular' | 'vertical' | 'json' | 'unknown';

export interface ParsedExplain {
  rows: ExplainRow[];
  format: ExplainFormat;
  /** Set when the text looked like something but yielded no rows. */
  error: string | null;
}

const EMPTY: ParsedExplain = { rows: [], format: 'unknown', error: null };

const VERTICAL_ROW = /^\*+\s*\d+\.\s*row\s*\*+$/m;

export function parseExplain(text: string): ParsedExplain {
  const trimmed = (text || '').trim();
  if (!trimmed) {
    return EMPTY;
  }
  if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
    return parseJson(trimmed);
  }
  if (VERTICAL_ROW.test(trimmed)) {
    return parseVertical(trimmed);
  }
  return parseTabular(trimmed);
}

// ── tabular (TSV or CLI grid) ──

function parseTabular(text: string): ParsedExplain {
  const lines = text.split('\n')
    .map(line => line.trim())
    .filter(line => line.length > 0 && !/^\+[-+]*\+$/.test(line));
  if (lines.length < 2) {
    return { rows: [], format: 'tabular', error: 'expected a header line and at least one row' };
  }
  const split = (line: string): string[] => line.includes('|')
    ? line.replace(/^\|/, '').replace(/\|$/, '').split('|').map(cell => cell.trim())
    : line.split('\t').map(cell => cell.trim());

  const header = split(lines[0]).map(name => name.toLowerCase());
  if (!header.includes('id') || !header.includes('select_type')) {
    return { rows: [], format: 'tabular', error: 'no EXPLAIN header found (need id / select_type columns)' };
  }
  const rows: ExplainRow[] = [];
  for (const line of lines.slice(1)) {
    const cells = split(line);
    if (cells.length < 2) {
      continue;
    }
    const cell = (name: string): string | null => {
      const index = header.indexOf(name);
      return index < 0 || index >= cells.length ? null : nullable(cells[index]);
    };
    rows.push(buildRow({
      id: cell('id'),
      selectType: cell('select_type'),
      table: cell('table'),
      partitions: cell('partitions'),
      type: cell('type'),
      possibleKeys: cell('possible_keys'),
      key: cell('key'),
      keyLen: cell('key_len'),
      ref: cell('ref'),
      rows: cell('rows'),
      filtered: cell('filtered'),
      extra: cell('extra')
    }));
  }
  return finish(rows, 'tabular');
}

// ── vertical (\G) ──

function parseVertical(text: string): ParsedExplain {
  const blocks = text.split(/^\*+\s*\d+\.\s*row\s*\*+$/m).slice(1);
  const rows: ExplainRow[] = [];
  for (const block of blocks) {
    const values = new Map<string, string>();
    for (const line of block.split('\n')) {
      const match = /^\s*([A-Za-z_]+):\s?(.*)$/.exec(line);
      if (match) {
        values.set(match[1].toLowerCase(), match[2].trim());
      }
    }
    if (values.size === 0) {
      continue;
    }
    const cell = (name: string) => nullable(values.get(name) ?? null);
    rows.push(buildRow({
      id: cell('id'),
      selectType: cell('select_type'),
      table: cell('table'),
      partitions: cell('partitions'),
      type: cell('type'),
      possibleKeys: cell('possible_keys'),
      key: cell('key'),
      keyLen: cell('key_len'),
      ref: cell('ref'),
      rows: cell('rows'),
      filtered: cell('filtered'),
      extra: cell('extra')
    }));
  }
  return finish(rows, 'vertical');
}

// ── EXPLAIN FORMAT=JSON ──

function parseJson(text: string): ParsedExplain {
  let document: unknown;
  try {
    document = JSON.parse(text);
  } catch (error) {
    return { rows: [], format: 'json', error: 'invalid JSON: ' + (error as Error).message };
  }
  const rows: ExplainRow[] = [];
  walkJson(document, rows);
  return finish(rows, 'json');
}

/** Collect every object carrying a table_name — those are the access nodes. */
function walkJson(node: unknown, out: ExplainRow[]): void {
  if (Array.isArray(node)) {
    node.forEach(child => walkJson(child, out));
    return;
  }
  if (node === null || typeof node !== 'object') {
    return;
  }
  const record = node as Record<string, unknown>;
  if (typeof record['table_name'] === 'string') {
    out.push(buildRow({
      id: null,
      selectType: null,
      table: record['table_name'] as string,
      partitions: joinIfArray(record['partitions']),
      type: str(record['access_type']),
      possibleKeys: joinIfArray(record['possible_keys']),
      key: str(record['key']),
      keyLen: str(record['key_length']),
      ref: joinIfArray(record['ref']),
      rows: str(record['rows_examined_per_scan']),
      filtered: str(record['filtered']),
      extra: extraFromJson(record)
    }));
  }
  Object.values(record).forEach(child => walkJson(child, out));
}

function extraFromJson(record: Record<string, unknown>): string | null {
  const notes: string[] = [];
  if (record['using_filesort'] === true) { notes.push('Using filesort'); }
  if (record['using_temporary_table'] === true) { notes.push('Using temporary'); }
  if (record['using_index'] === true) { notes.push('Using index'); }
  if (typeof record['attached_condition'] === 'string') { notes.push('Using where'); }
  return notes.length ? notes.join('; ') : null;
}

function str(value: unknown): string | null {
  return value === null || value === undefined ? null : String(value);
}

function joinIfArray(value: unknown): string | null {
  if (Array.isArray(value)) {
    return value.join(',');
  }
  return str(value);
}

// ── shared ──

interface RawRow {
  id: string | null;
  selectType: string | null;
  table: string | null;
  partitions: string | null;
  type: string | null;
  possibleKeys: string | null;
  key: string | null;
  keyLen: string | null;
  ref: string | null;
  rows: string | null;
  filtered: string | null;
  extra: string | null;
}

function buildRow(raw: RawRow): ExplainRow {
  return {
    id: raw.id,
    selectType: raw.selectType,
    table: raw.table,
    partitions: raw.partitions,
    type: raw.type,
    possibleKeys: raw.possibleKeys ? raw.possibleKeys.split(',').map(key => key.trim()).filter(Boolean) : [],
    key: raw.key,
    keyLen: raw.keyLen,
    ref: raw.ref,
    rows: toNumber(raw.rows),
    filtered: toNumber(raw.filtered),
    extra: raw.extra
  };
}

function nullable(value: string | null): string | null {
  if (value === null) {
    return null;
  }
  const trimmed = value.trim();
  return trimmed === '' || trimmed.toUpperCase() === 'NULL' ? null : trimmed;
}

function toNumber(value: string | null): number | null {
  if (value === null) {
    return null;
  }
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function finish(rows: ExplainRow[], format: ExplainFormat): ParsedExplain {
  return {
    rows,
    format,
    error: rows.length === 0 ? `looked like ${format} EXPLAIN output but no rows could be parsed` : null
  };
}
