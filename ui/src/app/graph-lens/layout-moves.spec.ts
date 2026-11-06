import {layoutGrid, LayoutBox} from './grid-layout';
import {tableKey} from './aggregates';
import {planMove, SavedLayout, slotAt} from './layout-moves';

describe('SavedLayout', () => {

  const saved = new SavedLayout([
    { kind: 'table', schema: 'app_main', name: 'account_site', col: 1, row: 0, override: true, parent: 'site' },
    { kind: 'aggregate', schema: 'app_main', name: 'invoice', col: 2, row: 3, override: false, parent: null },
    { kind: 'table', schema: 'app_main', name: 'note', col: null, row: null, override: true, parent: null }
  ]);

  it('reads slots and overrides, case-insensitively', () => {
    expect(saved.slot('aggregate', 'APP_MAIN', 'Invoice')).toEqual({ col: 2, row: 3 });
    expect(saved.slot('table', 'app_main', 'note')).toBeUndefined();
    const overrides = saved.overrides();
    expect(overrides.get(tableKey('app_main', 'account_site'))).toBe('site');
    expect(overrides.has(tableKey('app_main', 'note'))).toBeTrue();
    expect(overrides.get(tableKey('app_main', 'note'))).toBeNull();
  });

  it('merges a change into what is saved: a new slot keeps the override, and an override keeps the slot', () => {
    const moved = saved.apply([{ kind: 'table', schema: 'app_main', name: 'account_site', slot: { col: 4, row: 4 } }]);
    expect(moved.written).toEqual([
      { kind: 'table', schema: 'app_main', name: 'account_site', col: 4, row: 4, override: true, parent: 'site' }]);

    const regrouped = moved.layout.apply([{ kind: 'table', schema: 'app_main', name: 'account_site', parent: null }]);
    expect(regrouped.written[0]).toEqual(
      { kind: 'table', schema: 'app_main', name: 'account_site', col: 4, row: 4, override: true, parent: null });
    // the original is untouched
    expect(saved.slot('table', 'app_main', 'account_site')).toEqual({ col: 1, row: 0 });
  });

  it('drops a hand-chosen aggregate on request, keeping the slot', () => {
    const { written, layout } = saved.apply([{ kind: 'table', schema: 'app_main', name: 'account_site', inferred: true }]);
    expect(written).toEqual([
      { kind: 'table', schema: 'app_main', name: 'account_site', col: 1, row: 0, override: false, parent: null }]);
    expect(layout.overrides().has(tableKey('app_main', 'account_site'))).toBeFalse();
  });

  it('starts an item nothing was saved for', () => {
    const { written } = saved.apply([{ kind: 'schema', schema: 'App_Billing', name: '', slot: { col: 1, row: 0 } }]);
    expect(written).toEqual([
      { kind: 'schema', schema: 'app_billing', name: '', col: 1, row: 0, override: false, parent: null }]);
  });
});

describe('slotAt', () => {

  const SIZE = { w: 100, h: 40 };
  const cells: LayoutBox[] = layoutGrid([
    { key: 'a', size: SIZE, slot: { col: 0, row: 0 } },
    { key: 'b', size: SIZE, slot: { col: 3, row: 0 } },
    { key: 'c', size: SIZE, slot: { col: 0, row: 2 } }
  ], [{ gap: 20, pad: 0, header: 0 }]);
  // drawn compacted: a at (0,0), b at (120,0), c at (0,60)

  it('reads the slot a point is in, as saved rather than as drawn', () => {
    expect(slotAt(cells, 150, 10)).toEqual({ col: 3, row: 0 });
    expect(slotAt(cells, 150, 70)).toEqual({ col: 3, row: 2 });
  });

  it('takes the nearer column in a gutter', () => {
    expect(slotAt(cells, 104, 10).col).toBe(0);
    expect(slotAt(cells, 116, 10).col).toBe(3);
  });

  it('opens a new column or row past the last, and never one before the first', () => {
    expect(slotAt(cells, 300, 10)).toEqual({ col: 4, row: 0 });
    expect(slotAt(cells, 10, 200)).toEqual({ col: 0, row: 3 });
    expect(slotAt(cells, -80, -80)).toEqual({ col: 0, row: 0 });
  });

  it('puts a point in an empty container at the origin', () => {
    expect(slotAt([], 50, 50)).toEqual({ col: 0, row: 0 });
  });
});

describe('planMove', () => {

  const cells = [
    { key: 'a', slot: { col: 0, row: 0 } },
    { key: 'b', slot: { col: 1, row: 0 } },
    { key: 'c', slot: { col: 0, row: 1 } }
  ];

  it('swaps with the cell at the target when the mover came from this container', () => {
    const plan = planMove(cells, 'a', { col: 1, row: 0 }, { col: 0, row: 0 });
    expect(plan.get('a')).toEqual({ col: 1, row: 0 });
    expect(plan.get('b')).toEqual({ col: 0, row: 0 });
    expect(plan.get('c')).toEqual({ col: 0, row: 1 });
  });

  it('moves into a free slot without disturbing anything', () => {
    const plan = planMove(cells, 'a', { col: 2, row: 2 }, { col: 0, row: 0 });
    expect(plan.get('a')).toEqual({ col: 2, row: 2 });
    expect(plan.get('b')).toEqual({ col: 1, row: 0 });
  });

  it('steps the occupant to the nearest free slot when the mover came from elsewhere', () => {
    const plan = planMove(cells, 'x', { col: 0, row: 0 }, null);
    expect(plan.get('x')).toEqual({ col: 0, row: 0 });
    // (1,0) and (0,1) are taken, so the nearest free is two steps away
    const moved = plan.get('a')!;
    expect(Math.abs(moved.col) + Math.abs(moved.row)).toBe(2);
    expect(new Set([...plan.values()].map(slot => `${slot.col},${slot.row}`)).size).toBe(4);
  });

  it('freezes every cell of the container, so automatic placement cannot reshuffle them', () => {
    expect([...planMove(cells, 'c', { col: 5, row: 5 }, { col: 0, row: 1 }).keys()].sort())
      .toEqual(['a', 'b', 'c']);
  });
});
