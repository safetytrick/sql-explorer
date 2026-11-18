/**
 * The treemap layout (sqlx-7o0): every table a tile whose area is what it
 * measures (its query time, or its size on disk), nested in the blocks that
 * hold it: schema, then aggregate.
 *
 * <p>Squarified (Bruls, Huizing and van Wijk): tiles are laid in rows along
 * the shorter side of what is left, and a row is closed as soon as adding to
 * it would make its worst tile less square. Long slivers are what make a
 * treemap unreadable, so that is the one thing it optimises.
 *
 * <p>A block keeps a strip for its name and an inset round its children, so
 * a table's share of the whole is close to, not exactly, its share of the
 * area. A block too small for that keeps nothing, so no table with a value
 * is ever squeezed down to no area at all. Pure: rectangles only. Drawing lives in the component.
 */

export interface TreemapItem {
  key: string;
  /** What the tile measures; a block's is the sum of its children's. Zero and below are left out. */
  value: number;
  children?: TreemapItem[];
}

export interface Rect {
  x: number;
  y: number;
  w: number;
  h: number;
}

export interface Tile extends Rect {
  key: string;
  value: number;
  /** 0 for the outermost blocks. */
  depth: number;
  children: Tile[];
}

/** The room a block keeps at one depth: a strip on top for its name, and an inset all round. */
export interface TreemapPadding {
  header: number;
  inset: number;
}

/** A block's value: its own for a leaf, its children's for a block. */
export function valueOf(item: TreemapItem): number {
  return item.children ? item.children.reduce((sum, child) => sum + Math.max(0, valueOf(child)), 0) : item.value;
}

/**
 * Lay a tree of items out in a rectangle. `padding[depth]` is the room a block
 * at that depth keeps for itself; a depth with none keeps none. Items worth
 * nothing take no room and are not returned.
 */
export function treemap(items: TreemapItem[], rect: Rect, padding: TreemapPadding[] = [], depth = 0): Tile[] {
  const valued = items.map(item => ({item, value: valueOf(item)}))
    .filter(entry => entry.value > 0)
    // largest first, then by key, so equal values land in the same place on every load
    .sort((a, b) => b.value - a.value || a.item.key.localeCompare(b.item.key));
  const rects = squarify(valued.map(entry => entry.value), rect);
  return valued.map(({item, value}, index) => {
    const tile: Tile = {key: item.key, value, depth, ...rects[index], children: []};
    if (item.children) {
      // a block too small to hold its name gives its children all of it, rather than squeezing them out
      const wanted = padding[depth] ?? {header: 0, inset: 0};
      const roomy = tile.w > 4 * (wanted.header + wanted.inset) && tile.h > 2 * (wanted.header + wanted.inset);
      const pad = roomy ? wanted : {header: 0, inset: 0};
      const inner = {
        x: tile.x + pad.inset,
        y: tile.y + pad.header + pad.inset,
        w: Math.max(0, tile.w - 2 * pad.inset),
        h: Math.max(0, tile.h - pad.header - 2 * pad.inset)
      };
      tile.children = treemap(item.children, inner, padding, depth + 1);
    }
    return tile;
  });
}

/** Rectangles for values (largest first) that exactly fill `rect`, in the same order. */
export function squarify(values: number[], rect: Rect): Rect[] {
  const total = values.reduce((sum, value) => sum + value, 0);
  if (!values.length || total <= 0) { return []; }
  const scale = rect.w * rect.h / total;
  const areas = values.map(value => value * scale);
  const placed: Rect[] = [];
  let free = {...rect};
  let row: number[] = [];
  const worst = (candidate: number[], side: number) => {
    const sum = candidate.reduce((a, b) => a + b, 0);
    if (!sum || !side) { return Infinity; }
    return Math.max(...candidate.map(area => Math.max(side * side * area / (sum * sum), sum * sum / (side * side * area))));
  };
  const lay = () => {
    const sum = row.reduce((a, b) => a + b, 0);
    if (free.w >= free.h) {
      // a column down the left of what is free
      const width = free.h ? sum / free.h : 0;
      let y = free.y;
      for (const area of row) {
        const height = width ? area / width : 0;
        placed.push({x: free.x, y, w: width, h: height});
        y += height;
      }
      free = {x: free.x + width, y: free.y, w: Math.max(0, free.w - width), h: free.h};
    } else {
      // a row across the top
      const height = free.w ? sum / free.w : 0;
      let x = free.x;
      for (const area of row) {
        const width = height ? area / height : 0;
        placed.push({x, y: free.y, w: width, h: height});
        x += width;
      }
      free = {x: free.x, y: free.y + height, w: free.w, h: Math.max(0, free.h - height)};
    }
    row = [];
  };
  for (const area of areas) {
    const side = Math.min(free.w, free.h);
    if (row.length && worst([...row, area], side) > worst(row, side)) { lay(); }
    row.push(area);
  }
  if (row.length) { lay(); }
  return placed;
}

/**
 * A tile cut into parts by value, along its longer side, so each part stays
 * as square as the tile allows: the treemap's disk view splits a table into
 * its data and its indexes this way. A part worth nothing gets no room.
 */
export function splitTile(rect: Rect, values: number[]): Rect[] {
  const total = values.reduce((sum, value) => sum + Math.max(0, value), 0);
  if (total <= 0) { return values.map(() => ({x: rect.x, y: rect.y, w: 0, h: 0})); }
  const across = rect.w >= rect.h;
  let offset = 0;
  return values.map(value => {
    const share = Math.max(0, value) / total;
    const part = across
      ? {x: rect.x + offset * rect.w, y: rect.y, w: share * rect.w, h: rect.h}
      : {x: rect.x, y: rect.y + offset * rect.h, w: rect.w, h: share * rect.h};
    offset += share;
    return part;
  });
}
