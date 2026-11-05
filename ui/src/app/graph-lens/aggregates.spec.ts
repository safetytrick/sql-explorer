import {AggregateTable, buildAggregates, inferParents, Relation, singular, tableKey} from './aggregates';

const t = (table: string, schema = 'app_main'): AggregateTable => ({ schema, table });
const rel = (a: string, b: string, schema = 'app_main'): Relation => ({ a: t(a, schema), b: t(b, schema) });
const roots = (tables: AggregateTable[], relations: Relation[], overrides?: Map<string, string | null>) =>
  buildAggregates(tables, relations, overrides).map(aggregate => aggregate.tables.join(','));

describe('singular', () => {
  it('handles the common plural shapes and leaves singulars alone', () => {
    expect(singular('invoices')).toBe('invoice');
    expect(singular('categories')).toBe('category');
    expect(singular('addresses')).toBe('address');
    expect(singular('boxes')).toBe('box');
    expect(singular('address')).toBeNull();
    expect(singular('account')).toBeNull();
  });
});

describe('inferParents', () => {

  it('needs a relation as well as a name match', () => {
    const tables = [t('invoice'), t('invoice_item')];
    expect(inferParents(tables, []).size).toBe(0);
    expect(inferParents(tables, [rel('invoice_item', 'invoice')]).get(tableKey('app_main', 'invoice_item')))
      .toBe('invoice');
  });

  it('counts a relation in either direction, so a join with no foreign key behind it is enough', () => {
    expect(inferParents([t('invoice'), t('invoice_item')], [rel('invoice', 'invoice_item')]).size).toBe(1);
  });

  it('matches a plural root by its singular', () => {
    const parents = inferParents([t('invoices'), t('invoice_items')], [rel('invoice_items', 'invoices')]);
    expect(parents.get(tableKey('app_main', 'invoice_items'))).toBe('invoices');
  });

  it('does not match on a name that merely starts the same', () => {
    // `invoicer` is not `invoice_` anything
    expect(inferParents([t('invoice'), t('invoicer')], [rel('invoicer', 'invoice')]).size).toBe(0);
  });

  it('prefers the most specific related root', () => {
    const tables = [t('invoice'), t('invoice_item'), t('invoice_item_tax')];
    const parents = inferParents(tables, [
      rel('invoice_item', 'invoice'), rel('invoice_item_tax', 'invoice_item'), rel('invoice_item_tax', 'invoice')]);
    expect(parents.get(tableKey('app_main', 'invoice_item_tax'))).toBe('invoice_item');
  });

  it('never crosses schemas', () => {
    const tables = [t('invoice', 'app_main'), t('invoice_item', 'app_billing')];
    const relations = [{ a: t('invoice_item', 'app_billing'), b: t('invoice', 'app_main') }];
    expect(inferParents(tables, relations).size).toBe(0);
  });
});

describe('buildAggregates', () => {

  const tables = [t('account'), t('account_site'), t('invoice'), t('invoice_item'), t('invoice_item_tax'), t('site')];
  const relations = [
    rel('invoice_item', 'invoice'), rel('invoice_item_tax', 'invoice_item'),
    rel('account_site', 'account'), rel('account_site', 'site')
  ];

  it('flattens nested matches into the top root, and leaves unrelated tables on their own', () => {
    expect(roots(tables, relations)).toEqual([
      'account,account_site', 'invoice,invoice_item,invoice_item_tax', 'site']);
  });

  it('moves a table, and what hangs off it, by override', () => {
    const overrides = new Map([[tableKey('app_main', 'invoice_item'), 'site']]);
    expect(roots(tables, relations, overrides)).toEqual([
      'account,account_site', 'invoice', 'site,invoice_item,invoice_item_tax']);
  });

  it('detaches a table with a null override', () => {
    const overrides = new Map<string, string | null>([[tableKey('app_main', 'account_site'), null]]);
    expect(roots(tables, relations, overrides)).toContain('account_site');
    expect(roots(tables, relations, overrides)).toContain('account');
  });

  it('ignores an override naming a table that is not in the graph', () => {
    const overrides = new Map([[tableKey('app_main', 'invoice_item'), 'not_here']]);
    expect(roots(tables, relations, overrides)).toContain('invoice_item,invoice_item_tax');
  });

  it('resolves an override loop the same way whatever the input order', () => {
    const overrides = new Map([
      [tableKey('app_main', 'invoice'), 'site'], [tableKey('app_main', 'site'), 'invoice']]);
    const forward = roots(tables, relations, overrides);
    const backward = roots([...tables].reverse(), relations, overrides);
    expect(forward).toEqual(backward);
    expect(forward).toContain('invoice,invoice_item,invoice_item_tax,site');
  });

  it('puts every table in exactly one aggregate', () => {
    const all = buildAggregates(tables, relations).flatMap(aggregate => aggregate.tables);
    expect(all.sort()).toEqual(tables.map(table => table.table).sort());
  });
});
