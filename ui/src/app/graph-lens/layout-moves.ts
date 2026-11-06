/**
 * Moving things around the grid by hand, and the saved layout that results
 * (sqlx-aoy.15). Pure: the component turns pointer events into these calls
 * and their results into writes.
 *
 * <p>The saved layout is shared by everyone and keyed by schema name, so the
 * same schema keeps its arrangement in every environment. Each item records a
 * slot in the grid it sits in, and a table can also record the aggregate it
 * belongs to, overriding the inferred one.
 */
import {LayoutBox, Slot} from './grid-layout';
import {AggregateOverrides, tableKey} from './aggregates';

export type LayoutKind = 'schema' | 'aggregate' | 'table';

/** One saved item, as the API reads and writes it. Names are lower-case. */
export interface LayoutItem {
  kind: LayoutKind;
  schema: string;
  /** The aggregate's root or the table; empty for a schema. */
  name: string;
  col: number | null;
  row: number | null;
  /** Tables only: the aggregate was chosen by hand. */
  override: boolean;
  /** With `override`: the table this one belongs under; null for a root of its own. */
  parent: string | null;
}

export const itemKey = (kind: LayoutKind, schema: string, name: string) =>
  `${kind}:${schema.toLowerCase()}.${name.toLowerCase()}`;

/** The saved layout, indexed for the grid to read. */
export class SavedLayout {
  private readonly items = new Map<string, LayoutItem>();

  constructor(items: LayoutItem[] = []) {
    for (const item of items) { this.items.set(itemKey(item.kind, item.schema, item.name), item); }
  }

  get(kind: LayoutKind, schema: string, name: string): LayoutItem | undefined {
    return this.items.get(itemKey(kind, schema, name));
  }

  slot(kind: LayoutKind, schema: string, name: string): Slot | undefined {
    const item = this.get(kind, schema, name);
    return item && item.col !== null && item.row !== null ? { col: item.col, row: item.row } : undefined;
  }

  /** Aggregate overrides, keyed the way {@link buildAggregates} reads them. */
  overrides(): AggregateOverrides {
    const result: AggregateOverrides = new Map();
    for (const item of this.items.values()) {
      if (item.kind === 'table' && item.override) { result.set(tableKey(item.schema, item.name), item.parent); }
    }
    return result;
  }

  /**
   * A copy with these changes applied, each merged into what is saved for its
   * item: a slot change keeps the table's override, and an override keeps its
   * slot. Returns the full items written, which is what to send.
   */
  apply(changes: LayoutChange[]): { layout: SavedLayout; written: LayoutItem[] } {
    const next = new SavedLayout([...this.items.values()]);
    const written = new Map<string, LayoutItem>();
    for (const change of changes) {
      const key = itemKey(change.kind, change.schema, change.name);
      const current = written.get(key) ?? next.items.get(key) ?? {
        kind: change.kind, schema: change.schema.toLowerCase(), name: change.name.toLowerCase(),
        col: null, row: null, override: false, parent: null
      };
      const merged: LayoutItem = { ...current };
      if (change.slot !== undefined) {
        merged.col = change.slot?.col ?? null;
        merged.row = change.slot?.row ?? null;
      }
      if (change.parent !== undefined) {
        merged.override = true;
        merged.parent = change.parent?.toLowerCase() ?? null;
      }
      if (change.inferred) {
        merged.override = false;
        merged.parent = null;
      }
      written.set(key, merged);
      next.items.set(key, merged);
    }
    return { layout: next, written: [...written.values()] };
  }

  all(): LayoutItem[] {
    return [...this.items.values()];
  }
}

/**
 * One change to the saved layout. `slot` undefined leaves the slot alone and
 * null clears it; `parent` undefined leaves the aggregate alone, null makes
 * the table a root of its own, and a name puts it under that table.
 */
export interface LayoutChange {
  kind: LayoutKind;
  schema: string;
  name: string;
  slot?: Slot | null;
  parent?: string | null;
  /** Drop the aggregate chosen by hand, so the table goes back to the inferred one. */
  inferred?: boolean;
}

/**
 * The slot under a point in a container's grid, read from where its cells
 * were drawn. A point between columns goes to the nearer one; a point well
 * past the last column or row opens a new one, so a grid can grow by
 * dropping beside it. Nothing opens before the first column or row: slots
 * are never negative, and dropping there lands in the first.
 */
export function slotAt(cells: LayoutBox[], x: number, y: number): Slot {
  if (!cells.length) { return { col: 0, row: 0 }; }
  const axis = (start: (box: LayoutBox) => number, size: (box: LayoutBox) => number,
                slotOf: (box: LayoutBox) => number, at: number): number => {
    const bands = new Map<number, { start: number; end: number; slot: number }>();
    for (const cell of cells) {
      const band = bands.get(slotOf(cell)) ?? { start: Infinity, end: -Infinity, slot: slotOf(cell) };
      band.start = Math.min(band.start, start(cell));
      band.end = Math.max(band.end, start(cell) + size(cell));
      bands.set(slotOf(cell), band);
    }
    const ordered = [...bands.values()].sort((a, b) => a.start - b.start);
    const last = ordered[ordered.length - 1];
    // past the last band by more than a cell's worth of gap: a new one
    if (at > last.end + 24) { return last.slot + 1; }
    let best = ordered[0];
    let bestDistance = Infinity;
    for (const band of ordered) {
      const distance = at < band.start ? band.start - at : at > band.end ? at - band.end : 0;
      if (distance < bestDistance) { best = band; bestDistance = distance; }
    }
    return best.slot;
  };
  return {
    col: axis(box => box.x, box => box.w, box => box.slot.col, x),
    row: axis(box => box.y, box => box.h, box => box.slot.row, y)
  };
}

/**
 * Every cell's slot in a container after one cell moves to `target`.
 *
 * <p>The rest of the container is frozen where it was drawn, so cells that
 * were placed automatically do not reshuffle around the move. A cell already
 * at the target swaps into the mover's old slot when the mover came from this
 * container (`from`); otherwise it steps to the nearest free slot.
 *
 * @param cells the container's cells as drawn, with the mover among them only
 *              if it was already in this container
 */
export function planMove(cells: { key: string; slot: Slot }[], mover: string, target: Slot,
                         from: Slot | null): Map<string, Slot> {
  const result = new Map<string, Slot>();
  const same = (a: Slot, b: Slot) => a.col === b.col && a.row === b.row;
  let displaced: string | null = null;
  for (const cell of cells) {
    if (cell.key === mover) { continue; }
    if (same(cell.slot, target)) { displaced = cell.key; continue; }
    result.set(cell.key, cell.slot);
  }
  result.set(mover, target);
  if (displaced) {
    result.set(displaced, from ?? nearestFree([...result.values()], target));
  }
  return result;
}

/** The free slot nearest `near`, by grid steps, ties to the earlier row then column. */
function nearestFree(taken: Slot[], near: Slot): Slot {
  const occupied = new Set(taken.map(slot => `${slot.col},${slot.row}`));
  for (let distance = 1; ; distance++) {
    const candidates: Slot[] = [];
    for (let row = near.row - distance; row <= near.row + distance; row++) {
      for (let col = near.col - distance; col <= near.col + distance; col++) {
        if (row < 0 || col < 0) { continue; }
        if (Math.abs(row - near.row) + Math.abs(col - near.col) !== distance) { continue; }
        if (!occupied.has(`${col},${row}`)) { candidates.push({ col, row }); }
      }
    }
    if (candidates.length) {
      return candidates.sort((a, b) => a.row - b.row || a.col - b.col)[0];
    }
  }
}
