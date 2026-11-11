import {IndexUsage} from '../schema-lens.service';
import {orderIndexesByColumns} from './index-card';

describe('orderIndexesByColumns', () => {
  const index = (name: string, columns: string[], extra: Partial<IndexUsage> = {}): IndexUsage =>
    ({name, primary: false, unique: false, columns, exists: true, hints: [], queries: 0, ...extra});

  it('keeps the primary key first, orders the rest column by column, and puts missing ones last', () => {
    const ordered = orderIndexesByColumns([
      index('idx_status', ['status']),
      index('gone_idx', [], {exists: false}),
      index('idx_site_status', ['site_id', 'status']),
      index('PRIMARY', ['id'], {primary: true, unique: true}),
      index('idx_site', ['Site_id']),
      index('idx_account', ['account_id', 'site_id'])
    ]);

    expect(ordered.map(i => i.name)).toEqual(
      ['PRIMARY', 'idx_account', 'idx_site', 'idx_site_status', 'idx_status', 'gone_idx']);
  });
});
