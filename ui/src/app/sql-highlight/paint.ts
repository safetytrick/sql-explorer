import {analyze, Analysis, ColumnUse, Source, SourceKind} from './sql-scopes';
import {assignColors} from './table-colors';

/**
 * The column lists of tables a page already holds, offered to the highlighter.
 * Optional everywhere: only the analyzed-sql page passes it, because only it
 * has the structures loaded anyway (for its index card). The highlighter
 * never fetches a schema itself.
 */
export interface SchemaHint {
  schema?: string | null;
  name: string;
  columns: readonly string[];
}

/** A table instance and the colour the highlighter gave it. */
export interface TableColor {
  /** Stable within one paint; matches `data-sqt` on the rendered spans. */
  id: number;
  kind: SourceKind;
  name: string;
  schema?: string;
  alias?: string;
  /** Palette slot, 0…PALETTE_SIZE-1. */
  slot: number;
  /** `sqt-c{slot}`, the class that carries the colour in every theme. */
  cssClass: string;
  /** What the query calls it: the alias when there is one, else the name. */
  label: string;
}

export interface Painted {
  analysis: Analysis;
  colors: Map<Source, number>;
  /** One entry per table instance, in order of declaration. */
  tables: TableColor[];
}

export function paintSql(sql: string, schema?: readonly SchemaHint[] | null): Painted {
  const analysis = analyze(sql, schema?.length ? {columnsOf: columnLookup(schema)} : {});
  const colors = assignColors(analysis.sources);
  const tables = analysis.sources.map(s => {
    const slot = colors.get(s)!;
    return {
      id: s.id, kind: s.kind, name: s.name, schema: s.schema, alias: s.alias,
      slot, cssClass: `sqt-c${slot}`, label: s.alias ?? s.name,
    };
  });
  return {analysis, colors, tables};
}

/** The instances of one base table, e.g. both sides of a self-join. */
export function instancesOf(painted: Painted, schema: string | null | undefined, table: string): TableColor[] {
  const t = table.toLowerCase();
  const sc = schema?.toLowerCase();
  return painted.tables.filter(x => x.kind === 'table' && x.name.toLowerCase() === t &&
    (!x.schema || !sc || x.schema.toLowerCase() === sc));
}

/**
 * Which instances of a table constrain a column — the colours an index card
 * should show for it. A table that appears once owns all of its columns, even
 * ones the highlighter could not place (the backend's analysis knows more
 * than syntax does), so it is attributed without consulting the uses.
 */
export function constrainingInstances(painted: Painted, instances: TableColor[], column: string): TableColor[] {
  if (instances.length <= 1) { return instances; }
  const c = column.toLowerCase();
  const ids = new Set(painted.analysis.uses
    .filter((u: ColumnUse) => u.constrains && u.column === c)
    .map(u => u.source.id));
  return instances.filter(i => ids.has(i.id));
}

function columnLookup(schema: readonly SchemaHint[]): (s: Source) => ReadonlySet<string> | undefined {
  const byName = new Map<string, {schema?: string; cols: Set<string>}[]>();
  for (const h of schema) {
    const key = h.name.toLowerCase();
    const list = byName.get(key) ?? [];
    list.push({schema: h.schema?.toLowerCase(), cols: new Set(h.columns.map(c => c.toLowerCase()))});
    byName.set(key, list);
  }
  return s => {
    const list = byName.get(s.name.toLowerCase());
    if (!list) { return undefined; }
    const sc = s.schema?.toLowerCase();
    return (sc ? list.find(x => x.schema === sc) : undefined)?.cols ?? list[0].cols;
  };
}
