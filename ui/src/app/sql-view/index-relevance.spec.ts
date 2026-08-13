import {buildIndexPanels} from './index-relevance';
import type {TableIndex, TableStructure} from '../api-types';

function index(name: string, columns: string[], unique = false): TableIndex {
  return {
    name, columns, unique,
    schema: 'app', identifier: name, indexType: 'BTREE', direction: 'A',
    cardinality: 1, pages: 1, filterCondition: ''
  };
}

function structure(schema: string, name: string, indices: TableIndex[]): TableStructure {
  return {
    schema, name, indices,
    type: 'TABLE', remarks: '', foreignKeys: [], columns: [],
    underlyingTables: null, viewColumns: null
  };
}

describe('buildIndexPanels', () => {

  const orders = structure('app', 'orders', [
    index('PRIMARY', ['id'], true),
    index('idx_status_created', ['status', 'created_at']),
    index('idx_created', ['created_at']),
    index('idx_owner', ['owner_id'], true)
  ]);

  it('ranks an index the query can actually use above one it merely touches', () => {
    // constrains `status` (leading on idx_status_created) and `created_at`
    // (leading on idx_created, but only the second column of the composite)
    const [panel] = buildIndexPanels([orders], ['app.orders.status'], 'app');

    expect(panel.indexes[0].index.name).toBe('idx_status_created');
    expect(panel.indexes[0].leading).toBe(true);
    expect(panel.indexes.find(i => i.index.name === 'idx_created')!.leading).toBe(false);
  });

  it('marks a composite touched only on a trailing column as not usable', () => {
    const [panel] = buildIndexPanels([orders], ['app.orders.created_at'], 'app');

    const composite = panel.indexes.find(i => i.index.name === 'idx_status_created')!;
    expect(composite.matched).toEqual(['created_at']);
    expect(composite.leading).toBe(false);

    // the single-column index on the same column IS usable, so it sorts first
    expect(panel.indexes[0].index.name).toBe('idx_created');
    expect(panel.indexes[0].leading).toBe(true);
  });

  it('hides PRIMARY unless the query constrains it', () => {
    const hidden = buildIndexPanels([orders], ['app.orders.status'], 'app')[0];
    expect(hidden.indexes.map(i => i.index.name)).not.toContain('PRIMARY');

    const shown = buildIndexPanels([orders], ['app.orders.id'], 'app')[0];
    expect(shown.indexes[0].index.name).toBe('PRIMARY');
    expect(shown.indexes[0].leading).toBe(true);
  });

  it('flags a table the query constrains with nothing indexed from those columns', () => {
    const [panel] = buildIndexPanels([orders], ['app.orders.note'], 'app');

    expect(panel.queryColumns).toEqual(['note']);
    expect(panel.noLeadingMatch).toBe(true);
    expect(panel.indexes.every(i => i.matched.length === 0)).toBe(true);
  });

  it('does not flag a table the query constrains nothing on', () => {
    const [panel] = buildIndexPanels([orders], [], 'app');

    expect(panel.queryColumns).toEqual([]);
    expect(panel.noLeadingMatch).toBe(false);
  });

  it('matches column names case-insensitively', () => {
    const [panel] = buildIndexPanels([orders], ['APP.ORDERS.STATUS'], 'app');

    expect(panel.indexes[0].index.name).toBe('idx_status_created');
    expect(panel.indexes[0].leading).toBe(true);
  });

  it('applies the query schema to an unqualified column reference', () => {
    const [panel] = buildIndexPanels([orders], ['orders.status'], 'app');

    expect(panel.queryColumns).toEqual(['status']);
  });

  it('keeps each table to its own columns', () => {
    const lines = structure('app', 'lines', [index('idx_order', ['order_id'])]);

    const panels = buildIndexPanels([orders, lines],
      ['app.orders.status', 'app.lines.order_id'], 'app');

    expect(panels[0].queryColumns).toEqual(['status']);
    expect(panels[1].queryColumns).toEqual(['order_id']);
    expect(panels[1].indexes[0].leading).toBe(true);
  });

  it('survives a table with no indexes at all', () => {
    const [panel] = buildIndexPanels([structure('app', 'bare', [])], ['app.bare.x'], 'app');

    expect(panel.indexes).toEqual([]);
    expect(panel.noLeadingMatch).toBe(true);
  });

  it('renders a repeated index name once', () => {
    // app_other.groups really does come back with two PRIMARY rows
    const dupes = structure('app', 'dupes', [
      index('PRIMARY', ['id'], true),
      index('PRIMARY', ['id'], true),
      index('idx_a', ['a'])
    ]);

    const [panel] = buildIndexPanels([dupes], ['app.dupes.id'], 'app');

    expect(panel.indexes.filter(i => i.index.name === 'PRIMARY').length).toBe(1);
  });
});
