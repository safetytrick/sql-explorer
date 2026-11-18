import {expect, test} from '@playwright/test';
import {SCOPED, stubApi} from './api';

const key = (name: string, over: Record<string, unknown> = {}) =>
  ({name, type: 'BIGINT', nullable: false, primary: false, unique: false, foreignKey: false, ...over});
const id = key('id', {primary: true, unique: true});
const node = (schema: string, table: string, columnCount: number, columns: unknown[]) =>
  ({schema, table, columnCount, queryCount: 3, warn: false, selfJoin: false, view: false, columns});
/** `a` many, `b` one: the shape of a join from a child's column to its parent's key. */
const pair = (a: string, b: string, over: Record<string, unknown> = {}) =>
  ({a, b, aUnique: false, bUnique: true, aNullable: false, bNullable: false, declared: null, ...over});
const join = (aTable: string, bTable: string, pairs: {a: string; b: string}[], bSchema = 'app_main') =>
  ({aSchema: 'app_main', aTable, bSchema, bTable, columns: pairs.map(p => `${p.a}=${p.b}`),
    queries: 2, executions: 1200, pairs});

const GRAPH = {
  nodes: [
    node('app_main', 'account', 14, [id]),
    node('app_main', 'account_site', 4, [id, key('account_id', {foreignKey: true}), key('site_id', {nullable: true})]),
    node('app_main', 'invoice', 22, [id, key('account_id'), key('number', {type: 'VARCHAR', unique: true})]),
    node('app_main', 'invoice_item', 9, [id, key('invoice_id', {foreignKey: true}), key('tax_code', {type: 'VARCHAR'})]),
    node('app_main', 'site', 11, [id]),
    node('app_other', 'tax', 5, [id, key('code', {type: 'VARCHAR'})])
  ],
  fkEdges: [
    {fromSchema: 'app_main', fromTable: 'invoice_item', toSchema: 'app_main', toTable: 'invoice',
      label: 'invoice_id→id', mismatch: false, joined: true, pairs: [pair('invoice_id', 'id', {declared: 'a'})]}
  ],
  coQueryEdges: [],
  viewEdges: [],
  joinEdges: [
    join('account_site', 'account', [pair('account_id', 'id', {declared: 'a'})]),
    join('account_site', 'site', [pair('site_id', 'id', {aNullable: true})]),
    join('invoice', 'account', [pair('account_id', 'id')]),
    join('invoice_item', 'invoice', [pair('invoice_id', 'id', {declared: 'a'})]),
    // neither end unique: many to many
    join('invoice_item', 'tax', [pair('tax_code', 'code', {bUnique: false})], 'app_other')
  ]
};

/**
 * The ERD is a layout of the workbench graph, so it is a link like any other
 * view: `var-layout=erd` opens it, and leaving it for the grid clears the
 * param (sqlx-929).
 */
test('the graph opens as an ERD from its link, and the toolbar switches it back to the grid', async ({page}, testInfo) => {
  await stubApi(page);
  await page.route(`${SCOPED}/graph/v2/**`, route =>
    route.fulfill({contentType: 'application/json', body: JSON.stringify(GRAPH)}));

  await page.goto('/queries?view=graph&var-layout=erd&var-legend=1');

  const erd = page.locator('.graph-toolbar').getByRole('button', {name: 'ERD', exact: true});
  await expect(erd).toHaveClass(/\bon\b/);
  // the ERD is arranged like the grid, so the grid's tools stay
  await expect(page.locator('.graph-toolbar').getByRole('button', {name: 'auto-place'})).toBeVisible();
  await expect(page.locator('.graph-legend')).toContainText('a unique index covers them');
  await testInfo.attach('erd', {body: await page.locator('app-graph-lens').screenshot(), contentType: 'image/png'});

  await erd.click();
  await expect(page).not.toHaveURL(/var-layout/);
  await expect(erd).not.toHaveClass(/\bon\b/);
  await expect(page.locator('.graph-legend')).not.toContainText('a unique index covers them');
});

/**
 * The pinned detail opens on its relationships, and its Columns tab lists
 * every column of the table, read from its metadata, as a link of its own.
 */
test('the selected table\'s detail lists its relationships, and every column on its Columns tab', async ({page}) => {
  await stubApi(page);
  await page.route(`${SCOPED}/graph/v2/**`, route =>
    route.fulfill({contentType: 'application/json', body: JSON.stringify(GRAPH)}));
  await page.route(`${SCOPED}/metadata/table/app_main/invoice_item`, route =>
    route.fulfill({contentType: 'application/json', body: JSON.stringify({
      type: 'TABLE', remarks: null, underlyingTables: null, viewColumns: null,
      indices: [{name: 'PRIMARY', unique: true, columns: ['id']}],
      columns: [
        {columnName: 'id', remarks: '', typeName: 'BIGINT', columnSize: 19, isNullable: 'NO', ordinal: 1},
        {columnName: 'invoice_id', remarks: '', typeName: 'BIGINT', columnSize: 19, isNullable: 'NO', ordinal: 2},
        {columnName: 'memo', remarks: '', typeName: 'VARCHAR', columnSize: 200, isNullable: 'YES', ordinal: 3}
      ]})}));

  await page.goto('/queries?view=graph&var-layout=erd&var-table=invoice_item');

  const detail = page.locator('.node-detail');
  await expect(detail.getByRole('tab', {name: 'Relationships'})).toHaveAttribute('aria-selected', 'true');
  await expect(detail).toContainText('joined with');
  await expect(detail.locator('.nd-actions').getByRole('link', {name: 'queries', exact: true})).toBeVisible();
  await expect(detail.getByRole('button', {name: '⊘ exclude queries'})).toBeVisible();

  await detail.getByRole('tab', {name: 'Columns'}).click();
  await expect(page).toHaveURL(/var-detail=columns/);
  await expect(detail.locator('.nd-cols tr')).toHaveText([/id\s*bigint\s*PK/, /invoice_id\s*bigint\s*FK/,
    /memo\s*varchar\(200\)\?/]);
});
