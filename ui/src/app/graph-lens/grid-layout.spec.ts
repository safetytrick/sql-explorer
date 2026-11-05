import {assignSlots, compact, extentOf, GridNode, GridSpacing, layoutGrid, LayoutBox} from './grid-layout';

const TABLE = { w: 130, h: 44 };
const table = (key: string, col?: number, row?: number): GridNode =>
  ({ key, size: TABLE, slot: col === undefined ? undefined : { col, row: row! } });
const FLAT: GridSpacing[] = [{ gap: 10, pad: 0, header: 0 }];

function overlaps(a: LayoutBox, b: LayoutBox): boolean {
  return a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;
}

/** No two siblings overlap, at any depth, and every child sits inside its parent. */
function expectNoOverlap(boxes: LayoutBox[]) {
  for (let i = 0; i < boxes.length; i++) {
    for (let j = i + 1; j < boxes.length; j++) {
      expect(overlaps(boxes[i], boxes[j]))
        .withContext(`${boxes[i].key} and ${boxes[j].key}`).toBeFalse();
    }
    for (const child of boxes[i].children) {
      expect(child.x >= boxes[i].x && child.y >= boxes[i].y
        && child.x + child.w <= boxes[i].x + boxes[i].w
        && child.y + child.h <= boxes[i].y + boxes[i].h)
        .withContext(`${child.key} inside ${boxes[i].key}`).toBeTrue();
    }
    expectNoOverlap(boxes[i].children);
  }
}

describe('assignSlots', () => {

  it('keeps a saved slot', () => {
    expect(assignSlots([{ key: 'account', slot: { col: 3, row: 2 } }]).get('account'))
      .toEqual({ col: 3, row: 2 });
  });

  it('fills unplaced nodes into a roughly square block, in key order', () => {
    const slots = assignSlots(['d', 'c', 'b', 'a'].map(key => ({ key })));
    expect(slots.get('a')).toEqual({ col: 0, row: 0 });
    expect(slots.get('b')).toEqual({ col: 1, row: 0 });
    expect(slots.get('c')).toEqual({ col: 0, row: 1 });
    expect(slots.get('d')).toEqual({ col: 1, row: 1 });
  });

  it('moves the later of two nodes claiming one slot, and is stable across input order', () => {
    const one = assignSlots([{ key: 'site', slot: { col: 0, row: 0 } }, { key: 'account', slot: { col: 0, row: 0 } }]);
    const two = assignSlots([{ key: 'account', slot: { col: 0, row: 0 } }, { key: 'site', slot: { col: 0, row: 0 } }]);
    expect(one).toEqual(two);
    expect(one.get('account')).toEqual({ col: 0, row: 0 });
    expect(one.get('site')).not.toEqual({ col: 0, row: 0 });
  });

  it('places a newcomer next to the slot it is near', () => {
    const slots = assignSlots([
      { key: 'invoice', slot: { col: 4, row: 4 } },
      { key: 'invoice_item' }
    ], { col: 4, row: 4 });
    const placed = slots.get('invoice_item')!;
    expect(Math.abs(placed.col - 4) + Math.abs(placed.row - 4)).toBe(1);
  });
});

describe('compact', () => {
  it('drops empty columns and rows, keeping order', () => {
    const dense = compact([{ col: 0, row: 0 }, { col: 5, row: 0 }, { col: 2, row: 7 }]);
    expect([...dense.col.entries()]).toEqual([[0, 0], [2, 1], [5, 2]]);
    expect([...dense.row.entries()]).toEqual([[0, 0], [7, 1]]);
  });
});

describe('layoutGrid', () => {

  it('places cells by slot, with gaps between them', () => {
    const boxes = layoutGrid([table('a', 0, 0), table('b', 1, 0), table('c', 0, 1)], FLAT);
    const at = (key: string) => boxes.find(box => box.key === key)!;
    expect([at('a').x, at('a').y]).toEqual([0, 0]);
    expect([at('b').x, at('b').y]).toEqual([140, 0]);
    expect([at('c').x, at('c').y]).toEqual([0, 54]);
  });

  it('closes the gap a filtered-out table leaves, keeping its neighbours in order', () => {
    const all = layoutGrid([table('a', 0, 0), table('b', 1, 0), table('c', 2, 0)], FLAT);
    const without = layoutGrid([table('a', 0, 0), table('c', 2, 0)], FLAT);
    expect(all.find(box => box.key === 'c')!.x).toBe(280);
    expect(without.find(box => box.key === 'c')!.x).toBe(140);
    expect(without.find(box => box.key === 'c')!.slot).toEqual({ col: 2, row: 0 });
  });

  it('sizes a container to its grid, with padding and a header', () => {
    const spacing: GridSpacing[] = [{ gap: 20, pad: 0, header: 0 }, { gap: 10, pad: 8, header: 16 }];
    const [schema] = layoutGrid([{ key: 'app_main', children: [table('a', 0, 0), table('b', 1, 0)] }], spacing);
    expect(schema.w).toBe(130 + 10 + 130 + 8 * 2);
    expect(schema.h).toBe(44 + 8 * 2 + 16);
    expect(schema.children[0].x).toBe(8);
    expect(schema.children[0].y).toBe(8 + 16);
  });

  it('widens a grid column to its widest container, so a big schema never sits on its neighbour', () => {
    const many = Array.from({ length: 24 }, (_, i) => table(`t${String(i).padStart(2, '0')}`));
    const spacing: GridSpacing[] = [{ gap: 30, pad: 0, header: 0 }, { gap: 10, pad: 12, header: 18 }];
    const boxes = layoutGrid([
      { key: 'app_big', slot: { col: 0, row: 0 }, children: many },
      { key: 'app_small', slot: { col: 1, row: 0 }, children: [table('x')] },
      { key: 'app_below', slot: { col: 0, row: 1 }, children: [table('y')] }
    ], spacing);
    expectNoOverlap(boxes);
    const big = boxes.find(box => box.key === 'app_big')!;
    expect(boxes.find(box => box.key === 'app_small')!.x).toBe(big.w + 30);
    expect(boxes.find(box => box.key === 'app_below')!.y).toBe(big.h + 30);
  });

  it('never overlaps, three levels deep, whatever the slots', () => {
    // environment → schema → aggregate → table, with colliding and missing slots
    const aggregate = (key: string, count: number, col?: number): GridNode => ({
      key, slot: col === undefined ? undefined : { col, row: 0 },
      children: Array.from({ length: count }, (_, i) => table(`${key}_${i}`, i % 2 ? undefined : 0, i))
    });
    const boxes = layoutGrid([
      { key: 'app_a', slot: { col: 0, row: 0 }, children: [aggregate('invoice', 5, 0), aggregate('account', 2, 0)] },
      { key: 'app_b', slot: { col: 0, row: 0 }, children: [aggregate('site', 7), aggregate('note', 1, 3)] },
      { key: 'app_c', children: [aggregate('log', 3)] }
    ], [{ gap: 40, pad: 0, header: 0 }, { gap: 20, pad: 14, header: 20 }, { gap: 10, pad: 8, header: 14 }]);
    expect(boxes.length).toBe(3);
    expectNoOverlap(boxes);
  });

  it('does not draw a container the filter has emptied', () => {
    const boxes = layoutGrid([
      { key: 'app_a', slot: { col: 0, row: 0 }, children: [] },
      { key: 'app_b', slot: { col: 1, row: 0 }, children: [table('x')] }
    ], [{ gap: 30, pad: 0, header: 0 }, { gap: 10, pad: 0, header: 0 }]);
    expect(boxes.map(box => box.key)).toEqual(['app_b']);
    expect(boxes[0].x).toBe(0);
  });

  it('reports the extent of what it laid out', () => {
    expect(extentOf(layoutGrid([table('a', 0, 0), table('b', 1, 1)], FLAT))).toEqual({ w: 270, h: 98 });
    expect(extentOf([])).toEqual({ w: 0, h: 0 });
  });
});
