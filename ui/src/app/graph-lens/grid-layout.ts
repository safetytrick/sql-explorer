/**
 * The graph's layout as nested grids, not pixels (sqlx-aoy.11).
 *
 * <p>An environment is a grid of schema cells, a schema a grid of aggregate
 * cells, an aggregate a grid of table cells. Every position is a whole-number
 * {@link Slot}, so a layout someone arranged by hand survives the graph
 * changing underneath it: a table that is filtered out leaves its slot empty,
 * and empty rows and columns are {@link compact compacted} away rather than
 * drawn as holes. Order and adjacency are what is kept, which is what makes
 * the picture recognisable from one filter to the next.
 *
 * <p>Overlap is impossible by construction rather than repaired afterwards:
 * a grid column is as wide as its widest cell and a row as tall as its
 * tallest, so a cell can never reach into its neighbour. The force layout
 * this replaces had to measure boxes and push them apart, and still left a
 * large schema sitting on its neighbour.
 *
 * <p>Everything here is pure: slots in, boxes out. Persistence and drawing
 * live elsewhere.
 */

export interface Slot {
  col: number;
  row: number;
}

/**
 * One cell of a grid: a leaf with a fixed size (a table), or a container
 * whose size is whatever its own grid of children needs.
 */
export interface GridNode {
  key: string;
  /** Where this cell sits in its parent's grid; absent means "place it". */
  slot?: Slot;
  /** A leaf's size. Ignored for containers, which size to their children. */
  size?: { w: number; h: number };
  children?: GridNode[];
}

/** Spacing for the cells of one level, keyed by depth (0 = the root's children). */
export interface GridSpacing {
  /** Space between neighbouring cells. */
  gap: number;
  /** Space between a container's edge and its cells. */
  pad: number;
  /** Extra space at the top of a container, for its label. */
  header: number;
}

/** A laid-out cell, in the root's coordinates. */
export interface LayoutBox {
  key: string;
  /** The slot the cell was drawn in, after placement (before compaction). */
  slot: Slot;
  x: number;
  y: number;
  w: number;
  h: number;
  children: LayoutBox[];
}

const slotKey = (slot: Slot) => `${slot.col},${slot.row}`;

/**
 * Give every node a slot of its own.
 *
 * <p>Nodes keep their slot when they have one and it is free. A node with no
 * slot, or one that collides with an earlier node, goes to the nearest free
 * slot to {@code near} (default the origin), ties broken row by row. Nodes
 * are handled in key order, so the outcome does not depend on the order the
 * data arrived in: a saved layout loaded twice comes out the same twice.
 *
 * <p>When nothing is near, unplaced nodes fill a roughly square block, so an
 * unarranged schema reads as a grid rather than one long row.
 */
export function assignSlots(nodes: { key: string; slot?: Slot }[], near?: Slot): Map<string, Slot> {
  const result = new Map<string, Slot>();
  const taken = new Set<string>();
  const ordered = [...nodes].sort((a, b) => a.key.localeCompare(b.key));
  const homeless: string[] = [];
  for (const node of ordered) {
    const slot = node.slot;
    if (slot && slot.col >= 0 && slot.row >= 0 && !taken.has(slotKey(slot))) {
      result.set(node.key, { col: slot.col, row: slot.row });
      taken.add(slotKey(slot));
    } else {
      homeless.push(node.key);
    }
  }
  const width = Math.max(1, Math.ceil(Math.sqrt(nodes.length)));
  for (const key of homeless) {
    const slot = nearestFree(taken, width, near);
    result.set(key, slot);
    taken.add(slotKey(slot));
  }
  return result;
}

/**
 * The free slot closest to {@code near}, searching a box one row and column
 * past everything taken — that margin always holds a free slot, so the search
 * is bounded. Without {@code near}, the first free slot row by row within
 * {@code width} columns.
 */
function nearestFree(taken: Set<string>, width: number, near?: Slot): Slot {
  let maxCol = width - 1;
  let maxRow = 0;
  for (const key of taken) {
    const [col, row] = key.split(',').map(Number);
    maxCol = Math.max(maxCol, col);
    maxRow = Math.max(maxRow, row);
  }
  if (!near) {
    for (let row = 0; ; row++) {
      for (let col = 0; col < width; col++) {
        if (!taken.has(slotKey({ col, row }))) { return { col, row }; }
      }
    }
  }
  let best: Slot | null = null;
  let bestDistance = Infinity;
  for (let row = 0; row <= maxRow + 1; row++) {
    for (let col = 0; col <= maxCol + 1; col++) {
      if (taken.has(slotKey({ col, row }))) { continue; }
      const distance = Math.abs(col - near.col) + Math.abs(row - near.row);
      if (distance < bestDistance) { best = { col, row }; bestDistance = distance; }
    }
  }
  return best!;
}

/**
 * Map slots onto dense column and row indices, dropping any column or row no
 * cell occupies. Relative order is preserved, so neighbours stay neighbours.
 */
export function compact(slots: Slot[]): { col: Map<number, number>; row: Map<number, number> } {
  const dense = (values: number[]) =>
    new Map([...new Set(values)].sort((a, b) => a - b).map((value, index) => [value, index]));
  return { col: dense(slots.map(slot => slot.col)), row: dense(slots.map(slot => slot.row)) };
}

const NO_SPACING: GridSpacing = { gap: 0, pad: 0, header: 0 };

/**
 * Lay out a tree of grids. Returns one box per root child, positioned from
 * the origin, each holding its own children's boxes.
 *
 * @param spacing per depth; the last entry repeats for anything deeper.
 */
export function layoutGrid(nodes: GridNode[], spacing: GridSpacing[]): LayoutBox[] {
  return layoutLevel(nodes, spacing, 0, 0, 0).boxes;
}

/** The overall size of a laid-out level, padding included. */
export function extentOf(boxes: LayoutBox[]): { w: number; h: number } {
  if (!boxes.length) { return { w: 0, h: 0 }; }
  return {
    w: Math.max(...boxes.map(box => box.x + box.w)) - Math.min(...boxes.map(box => box.x)),
    h: Math.max(...boxes.map(box => box.y + box.h)) - Math.min(...boxes.map(box => box.y))
  };
}

function layoutLevel(nodes: GridNode[], spacing: GridSpacing[], depth: number,
                     originX: number, originY: number): { boxes: LayoutBox[]; w: number; h: number } {
  // A container the filter has emptied is not drawn, and its slot compacts
  // away like any other empty one.
  nodes = nodes.filter(node => !node.children || node.children.length > 0);
  if (!nodes.length) { return { boxes: [], w: 0, h: 0 }; }
  const space = spacing[Math.min(depth, spacing.length - 1)] ?? NO_SPACING;
  const slots = assignSlots(nodes);

  // Size every cell first, at the origin: a container's size is its children's.
  const sized = nodes.map(node => {
    if (node.children?.length) {
      const inner = layoutLevel(node.children, spacing, depth + 1, 0, 0);
      const childSpace = spacing[Math.min(depth + 1, spacing.length - 1)] ?? NO_SPACING;
      return {
        node, inner,
        w: inner.w + childSpace.pad * 2,
        h: inner.h + childSpace.pad * 2 + childSpace.header
      };
    }
    return { node, inner: null, w: node.size?.w ?? 0, h: node.size?.h ?? 0 };
  });

  const dense = compact([...slots.values()]);
  const widths = new Array<number>(dense.col.size).fill(0);
  const heights = new Array<number>(dense.row.size).fill(0);
  for (const cell of sized) {
    const slot = slots.get(cell.node.key)!;
    const col = dense.col.get(slot.col)!;
    const row = dense.row.get(slot.row)!;
    widths[col] = Math.max(widths[col], cell.w);
    heights[row] = Math.max(heights[row], cell.h);
  }
  const offsets = (sizes: number[]) => {
    const result: number[] = [];
    let at = 0;
    for (const size of sizes) { result.push(at); at += size + space.gap; }
    return result;
  };
  const left = offsets(widths);
  const top = offsets(heights);

  const boxes = sized.map(cell => {
    const slot = slots.get(cell.node.key)!;
    const x = originX + left[dense.col.get(slot.col)!];
    const y = originY + top[dense.row.get(slot.row)!];
    const childSpace = spacing[Math.min(depth + 1, spacing.length - 1)] ?? NO_SPACING;
    const children = cell.inner
      ? shift(cell.inner.boxes, x + childSpace.pad, y + childSpace.pad + childSpace.header)
      : [];
    return { key: cell.node.key, slot, x, y, w: cell.w, h: cell.h, children };
  });
  const sum = (sizes: number[]) => sizes.reduce((total, size) => total + size, 0)
    + space.gap * Math.max(0, sizes.length - 1);
  return { boxes, w: sum(widths), h: sum(heights) };
}

function shift(boxes: LayoutBox[], dx: number, dy: number): LayoutBox[] {
  return boxes.map(box => ({
    ...box, x: box.x + dx, y: box.y + dy, children: shift(box.children, dx, dy)
  }));
}
