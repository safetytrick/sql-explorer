import {splitTile, squarify, treemap, valueOf} from './treemap';

describe('treemap', () => {
  const area = (rect: {w: number; h: number}) => rect.w * rect.h;

  it('fills the rectangle exactly, each value getting its share of the area', () => {
    const rects = squarify([6, 6, 4, 3, 2, 2, 1], {x: 0, y: 0, w: 600, h: 400});
    const total = 24;
    rects.forEach((rect, index) =>
      expect(area(rect)).toBeCloseTo([6, 6, 4, 3, 2, 2, 1][index] / total * 240_000, 3));
    // nothing outside the rectangle
    for (const rect of rects) {
      expect(rect.x).toBeGreaterThanOrEqual(-1e-9);
      expect(rect.y).toBeGreaterThanOrEqual(-1e-9);
      expect(rect.x + rect.w).toBeLessThanOrEqual(600 + 1e-6);
      expect(rect.y + rect.h).toBeLessThanOrEqual(400 + 1e-6);
    }
  });

  it('keeps tiles near square rather than slicing them into slivers', () => {
    const rects = squarify([6, 6, 4, 3, 2, 2, 1], {x: 0, y: 0, w: 600, h: 400});
    const worst = Math.max(...rects.map(rect => Math.max(rect.w / rect.h, rect.h / rect.w)));
    expect(worst).toBeLessThan(3);
  });

  it('nests children inside their block, below its name, and leaves out what measures nothing', () => {
    const tiles = treemap([
      {key: 's:app_main', value: 0, children: [
        {key: 'a:account', value: 0, children: [{key: 't:account', value: 30}, {key: 't:account_site', value: 10}]},
        {key: 't:site', value: 20},
        {key: 't:empty', value: 0}
      ]},
      {key: 's:app_other', value: 0, children: [{key: 't:tax', value: 0}]}
    ], {x: 0, y: 0, w: 300, h: 200}, [{header: 20, inset: 2}, {header: 10, inset: 1}]);

    expect(tiles.map(tile => tile.key)).toEqual(['s:app_main']);
    const schema = tiles[0];
    expect(schema.value).toBe(60);
    expect(schema.children.map(tile => tile.key)).toEqual(['a:account', 't:site']);
    const aggregate = schema.children[0];
    for (const child of [...schema.children, ...aggregate.children]) {
      expect(child.y).toBeGreaterThanOrEqual(schema.y + 20);
    }
    for (const child of aggregate.children) {
      expect(child.y).toBeGreaterThanOrEqual(aggregate.y + 10);
      expect(child.x + child.w).toBeLessThanOrEqual(aggregate.x + aggregate.w + 1e-6);
    }
    expect(aggregate.children.map(tile => tile.depth)).toEqual([2, 2]);
  });

  it('gives a block too small for its name all of its room, so its tables still get area', () => {
    const tiles = treemap([
      {key: 's:big', value: 0, children: [{key: 't:a', value: 10_000}]},
      {key: 's:small', value: 0, children: [{key: 't:b', value: 3}, {key: 't:c', value: 1}]}
    ], {x: 0, y: 0, w: 1600, h: 900}, [{header: 24, inset: 4}]);
    const small = tiles.find(tile => tile.key === 's:small')!;
    expect(small.children.length).toBe(2);
    for (const child of small.children) { expect(child.w * child.h).toBeGreaterThan(0); }
  });

  it('sums a block from its children, ignoring negative values', () => {
    expect(valueOf({key: 'b', value: 99, children: [{key: 'x', value: 2}, {key: 'y', value: -5}]})).toBe(2);
  });

  it('splits a tile by value along its longer side', () => {
    expect(splitTile({x: 10, y: 0, w: 400, h: 100}, [3, 1]))
      .toEqual([{x: 10, y: 0, w: 300, h: 100}, {x: 310, y: 0, w: 100, h: 100}]);
    expect(splitTile({x: 0, y: 0, w: 50, h: 200}, [1, 1]))
      .toEqual([{x: 0, y: 0, w: 50, h: 100}, {x: 0, y: 100, w: 50, h: 100}]);
    expect(splitTile({x: 0, y: 0, w: 50, h: 200}, [5, 0])[1].h).toBe(0);
  });
});
